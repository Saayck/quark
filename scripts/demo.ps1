param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'

# Direct access grant is enabled only for this local learning environment.
$token = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8180/realms/quark-bank/protocol/openid-connect/token' -Body @{
    grant_type = 'password'
    client_id = 'quark-bank-api'
    username = 'alice'
    password = 'alice-local-only'
}
$headers = @{ Authorization = "Bearer $($token.access_token)"; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$body = @{
    sourceAccountId = '10000000-0000-0000-0000-000000000001'
    destinationAccountId = '10000000-0000-0000-0000-000000000002'
    amount = 25.50
    currency = 'PEN'
} | ConvertTo-Json

Write-Output 'Cuentas de Alice antes de la transferencia:'
Invoke-RestMethod -Uri "$BaseUrl/api/accounts" -Headers $headers | Format-Table id,currency,balance
Write-Output 'Transferencia de PEN 25.50 de Alice a Bob:'
$first = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/transfers" -Headers $headers -ContentType 'application/json' -Body $body
$first | Format-List
Write-Output 'Reintento con la misma clave:'
$retry = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/transfers" -Headers $headers -ContentType 'application/json' -Body $body
if ($first.id -ne $retry.id) { throw 'El reintento debe devolver la misma transferencia.' }
Write-Output "Se devolvió la misma transferencia: $($retry.id)"
Write-Output 'Cuentas de Alice después de la transferencia:'
Invoke-RestMethod -Uri "$BaseUrl/api/accounts" -Headers $headers | Format-Table id,currency,balance
