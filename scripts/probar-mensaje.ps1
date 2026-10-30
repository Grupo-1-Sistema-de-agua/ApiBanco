[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_-]{8,64}$')]
    [string]$Clave = "EQA-TERMINAL-001",

    [long]$PaymentId = 999888,

    [string]$Monto = "125.50",

    [ValidateSet("CASH", "CARD", "BANK_TRANSFER", "CHECK")]
    [string]$Canal = "CASH",

    [string]$Descripcion = "Prueba desde terminal",

    [string]$RabbitHost = "localhost",
    [int]$RabbitPort = 15672,
    [string]$RabbitUser = "guest",
    [string]$RabbitPass = "guest"
)

$ErrorActionPreference = "Stop"

$payload = @{
    idempotencyKey  = $Clave
    paymentId       = $PaymentId
    customerCode    = "CLI-0001"
    fullName        = "Cliente de Prueba"
    taxId           = "CF"
    amount          = $Monto
    transactionDate = "2026-10-27T10:00:00-06:00"
    channel         = $Canal
    description     = $Descripcion
} | ConvertTo-Json -Compress

$body = @{
    properties = @{
        content_type = "application/json"
        delivery_mode = 2
    }
    routing_key = "banco"
    payload = $payload
    payload_encoding = "string"
} | ConvertTo-Json -Depth 5 -Compress

$auth = [Convert]::ToBase64String(
    [Text.Encoding]::ASCII.GetBytes("${RabbitUser}:${RabbitPass}")
)

$uri = "http://${RabbitHost}:${RabbitPort}/api/exchanges/%2F/pagos.exchange/publish"

$resp = Invoke-RestMethod -Method Post -Uri $uri `
    -Headers @{ Authorization = "Basic $auth" } `
    -ContentType "application/json" `
    -Body $body

Write-Host "Mensaje publicado: $Clave"
$resp | ConvertTo-Json -Compress
Write-Host ""
Write-Host "Revisa el consumidor:"
Write-Host "  docker compose logs -f pagos-consumer"
Write-Host ""
Write-Host "Revisa DynamoDB:"
Write-Host "  docker run --rm -e AWS_ACCESS_KEY_ID=dummy -e AWS_SECRET_ACCESS_KEY=dummy amazon/aws-cli dynamodb scan --table-name pagos_enviados --endpoint-url http://host.docker.internal:8000 --region us-east-1"
