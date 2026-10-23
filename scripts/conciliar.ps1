<#
.SYNOPSIS
    Concilia los pagos enviados (flag) contra los intentos registrados (log).

.DESCRIPTION
    Compara las tablas pagos_enviados y pagos_log de DynamoDB Local y detecta:
      - flag sin log EXITOSO
      - log EXITOSO sin flag
      - receiptId distinto entre el flag y el log
    Los intentos FALLIDO sin flag se muestran como informativos: es lo esperado
    cuando el pago termino en la DLQ.

    Por defecto solo analiza pagos reales (claves EQA-000000). Las claves de
    prueba (EQA-REHEARSAL-*, EQA-DEMO-*) se ignoran salvo que se use
    -IncluirPruebas.

.PARAMETER Endpoint
    URL de DynamoDB Local vista desde el contenedor de AWS CLI.
    Por defecto: http://host.docker.internal:8000

.PARAMETER Region
    Region de AWS. Por defecto: us-east-1

.PARAMETER TablaFlags
    Tabla de flags. Por defecto: pagos_enviados

.PARAMETER TablaLog
    Tabla de logs. Por defecto: pagos_log

.PARAMETER IncluirPruebas
    Incluye claves que no tienen el formato EQA-###### (por ejemplo, las de ensayo).

.EXAMPLE
    .\scripts\conciliar.ps1

.EXAMPLE
    .\scripts\conciliar.ps1 -IncluirPruebas

.NOTES
    Requiere Docker Desktop encendido y el contenedor dynamodb-local arriba.
    Sale con codigo 1 si encuentra diferencias (util para QA o CI).
#>
[CmdletBinding()]
param(
    [string]$Endpoint = "http://host.docker.internal:8000",
    [string]$Region = "us-east-1",
    [string]$TablaFlags = "pagos_enviados",
    [string]$TablaLog = "pagos_log",
    [string]$ImagenAws = "amazon/aws-cli",
    [switch]$IncluirPruebas
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker no esta disponible en el PATH. Enciende Docker Desktop."
}

function Get-Tabla {
    param([string]$Tabla)

    $dockerArgs = @(
        "run", "--rm",
        "-e", "AWS_ACCESS_KEY_ID=dummy",
        "-e", "AWS_SECRET_ACCESS_KEY=dummy",
        $ImagenAws, "dynamodb", "scan",
        "--table-name", $Tabla,
        "--endpoint-url", $Endpoint,
        "--region", $Region,
        "--output", "json"
    )

    $salida = & docker @dockerArgs 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "No se pudo escanear la tabla $Tabla (codigo $LASTEXITCODE). Esta DynamoDB arriba?"
    }
    if ([string]::IsNullOrWhiteSpace($salida)) {
        throw "Respuesta vacia al escanear $Tabla"
    }

    return (($salida -join [Environment]::NewLine) | ConvertFrom-Json)
}

function Test-PagoReal {
    param([string]$Clave)
    return $Clave -match '^EQA-\d{6}$'
}

function Show-Lista {
    param([string]$Titulo, $Lista)

    $items = @($Lista)
    Write-Host ""
    Write-Host ("{0} ({1})" -f $Titulo, $items.Count) -ForegroundColor Cyan
    if ($items.Count -eq 0) {
        Write-Host "  OK: ninguna" -ForegroundColor Green
    }
    else {
        $items | Sort-Object | ForEach-Object {
            Write-Host ("  - {0}" -f $_) -ForegroundColor Yellow
        }
    }
}

# ---------------------------------------------------------------------------
# 1. Leer tablas
# ---------------------------------------------------------------------------
$flagsScan = Get-Tabla $TablaFlags
$logScan = Get-Tabla $TablaLog

# ---------------------------------------------------------------------------
# 2. Flags (solo enviado = true)
# ---------------------------------------------------------------------------
$flags = @{}
foreach ($item in @($flagsScan.Items)) {
    $clave = [string]$item.idempotencyKey.S
    if (-not $IncluirPruebas -and -not (Test-PagoReal $clave)) { continue }

    $enviado = $false
    if ($item.PSObject.Properties.Name -contains "enviado") {
        $enviado = [bool]$item.enviado.BOOL
    }
    if (-not $enviado) { continue }

    $recibo = ""
    if ($item.PSObject.Properties.Name -contains "receiptId") {
        $recibo = [string]$item.receiptId.S
    }
    $flags[$clave] = [pscustomobject]@{ Recibo = $recibo }
}

# ---------------------------------------------------------------------------
# 3. Logs agrupados por idempotencyKey
# ---------------------------------------------------------------------------
$logs = @{}
foreach ($item in @($logScan.Items)) {
    $clave = [string]$item.idempotencyKey.S
    if (-not $IncluirPruebas -and -not (Test-PagoReal $clave)) { continue }

    if (-not $logs.ContainsKey($clave)) {
        $logs[$clave] = [pscustomobject]@{
            Exitoso  = $false
            Fallidos = 0
            Intentos = 0
            Recibo   = ""
            Ultimo   = ""
        }
    }

    $entrada = $logs[$clave]
    $entrada.Intentos++

    $estado = [string]$item.estado.S
    if ($estado -eq "EXITOSO") {
        $entrada.Exitoso = $true
        if ($item.PSObject.Properties.Name -contains "receiptId") {
            $entrada.Recibo = [string]$item.receiptId.S
        }
    }
    if ($estado -eq "FALLIDO") {
        $entrada.Fallidos++
    }

    $momento = [string]$item.momento.S
    if ($momento -gt $entrada.Ultimo) {
        $entrada.Ultimo = $momento
    }
}

# ---------------------------------------------------------------------------
# 4. Comparaciones
# ---------------------------------------------------------------------------
$flagsSinLog = New-Object System.Collections.Generic.List[string]
$logSinFlag = New-Object System.Collections.Generic.List[string]
$recibosDistintos = New-Object System.Collections.Generic.List[string]
$fallidosSinFlag = New-Object System.Collections.Generic.List[string]

foreach ($clave in $flags.Keys) {
    if (-not $logs.ContainsKey($clave) -or -not $logs[$clave].Exitoso) {
        $flagsSinLog.Add($clave)
    }
}

foreach ($clave in $logs.Keys) {
    $entrada = $logs[$clave]

    if ($entrada.Exitoso -and -not $flags.ContainsKey($clave)) {
        $logSinFlag.Add($clave)
    }
    if (-not $entrada.Exitoso -and -not $flags.ContainsKey($clave)) {
        $fallidosSinFlag.Add($clave)
    }
    if ($entrada.Exitoso -and $flags.ContainsKey($clave)) {
        $rFlag = $flags[$clave].Recibo
        $rLog = $entrada.Recibo
        if ($rFlag -ne "" -and $rLog -ne "" -and $rFlag -ne $rLog) {
            $recibosDistintos.Add(("{0} (flag={1}, log={2})" -f $clave, $rFlag, $rLog))
        }
    }
}

# ---------------------------------------------------------------------------
# 5. Resumen
# ---------------------------------------------------------------------------
$logsExitosos = @($logs.Values | Where-Object { $_.Exitoso }).Count
$intentosFallidos = ($logs.Values | Measure-Object -Property Fallidos -Sum).Sum
if ($null -eq $intentosFallidos) { $intentosFallidos = 0 }

Write-Host ""
Write-Host "=== Conciliacion Feature 3 ===" -ForegroundColor White
Write-Host ("Endpoint          : {0}" -f $Endpoint)
Write-Host ("Alcance           : {0}" -f $(if ($IncluirPruebas) { "todas las claves" } else { "solo pagos reales EQA-######" }))
Write-Host ("Flags enviados    : {0}" -f $flags.Count)
Write-Host ("Claves en log     : {0}" -f $logs.Count)
Write-Host ("Logs exitosos     : {0}" -f $logsExitosos)
Write-Host ("Intentos fallidos : {0}" -f $intentosFallidos)

Show-Lista "Flags sin log EXITOSO" $flagsSinLog
Show-Lista "Logs EXITOSOS sin flag" $logSinFlag
Show-Lista "ReceiptId distinto" $recibosDistintos
Show-Lista "Fallidos sin flag (informativo, pueden estar en DLQ)" $fallidosSinFlag

$problemas = $flagsSinLog.Count + $logSinFlag.Count + $recibosDistintos.Count

Write-Host ""
if ($problemas -gt 0) {
    Write-Host ("RESULTADO: CON DIFERENCIAS ({0})" -f $problemas) -ForegroundColor Red
    exit 1
}

Write-Host "RESULTADO: CONCILIADO" -ForegroundColor Green
exit 0
