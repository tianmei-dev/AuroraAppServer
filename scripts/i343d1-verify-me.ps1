# Verifies GET /api/v1/membership/me against standing 8081 using gitignored token file.
# Prints statuses only — never prints accessToken values.
param(
  [string]$TokenFile = ".local/i343d1/sessions.local.json",
  [string]$BaseUrl = "http://127.0.0.1:8081"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $TokenFile)) {
  Write-Error "Token file missing: $TokenFile (run scripts\i343d1-fixture.bat sessions first)"
}

$json = Get-Content $TokenFile -Raw | ConvertFrom-Json
$appCode = $json.appCode
$pass = 0
$fail = 0
$rows = @()

foreach ($acc in $json.accounts) {
  $label = $acc.label
  $token = $acc.accessToken
  if (-not $token) { continue }

  $hdr = @{
    "X-App-Code" = if ($label -eq "other-app-none") { "i343d1_other_app" } else { $appCode }
    "Authorization" = "Bearer $token"
  }

  try {
    $resp = Invoke-WebRequest -Method GET -Uri "$BaseUrl/api/v1/membership/me" -Headers $hdr -UseBasicParsing
    $body = $resp.Content | ConvertFrom-Json
    $status = $body.data.status
    $expected = $acc.expectedStatus
    $ok = $false
    if ($acc.expectedHttp) {
      $ok = $false
      $note = "expected HTTP $($acc.expectedHttp) but got $($resp.StatusCode)"
    } elseif ($expected -eq "VARIES") {
      $ok = ($body.code -eq 0)
      $note = "payment test account status=$status planType=$($body.data.planType) lifetime=$($body.data.isLifetime)"
    } elseif ($expected) {
      $ok = ($body.code -eq 0 -and $status -eq $expected)
      $note = "status=$status planType=$($body.data.planType) lifetime=$($body.data.isLifetime)"
    } else {
      $ok = $false
      $note = "no expectation"
    }
  } catch {
    $http = 0
    $code = $null
    if ($_.Exception.Response -ne $null) {
      $http = [int]$_.Exception.Response.StatusCode
    }
    $raw = $_.ErrorDetails.Message
    if ($raw) {
      try { $code = ($raw | ConvertFrom-Json).code } catch {}
    }
    if ($acc.expectedHttp) {
      $ok = ($http -eq [int]$acc.expectedHttp) -and ((-not $acc.expectedCode) -or ($code -eq [int]$acc.expectedCode))
      $note = "http=$http code=$code"
    } else {
      $ok = $false
      $note = "http=$http code=$code err"
    }
    $status = "(http)"
  }

  if ($ok) { $pass++ } else { $fail++ }
  $rows += [pscustomobject]@{ label = $label; expected = $(if ($acc.expectedStatus) { $acc.expectedStatus } else { "HTTP $($acc.expectedHttp)/$($acc.expectedCode)" }); pass = $ok; detail = $note }
}

# invalid token
try {
  Invoke-WebRequest -Method GET -Uri "$BaseUrl/api/v1/membership/me" -Headers @{
    "X-App-Code" = $appCode
    "Authorization" = "Bearer totally-invalid-token"
  } -UseBasicParsing | Out-Null
  $invOk = $false
  $invNote = "unexpected success"
} catch {
  $http = [int]$_.Exception.Response.StatusCode
  $invOk = ($http -eq 401)
  $invNote = "http=$http"
}
if ($invOk) { $pass++ } else { $fail++ }
$rows += [pscustomobject]@{ label = "invalid-token"; expected = "HTTP 401"; pass = $invOk; detail = $invNote }

$rows | Format-Table -AutoSize | Out-String | Write-Output
"PASS=$pass FAIL=$fail"
if ($fail -gt 0) { exit 1 } else { exit 0 }
