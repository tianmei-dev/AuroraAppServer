# HTTP-level fake payment acceptance against the isolated 8081 server (s32it + aurora_app_test).
# Never prints access tokens, the control token, or full payment links. DB checks are read-only
# SELECTs on aurora_app_test (3307). The expiry section needs a short order TTL (verification run: 2m);
# use -SkipExpiry against the default 30-minute server.
# Roles pay-1..pay-3 use the latest fresh batch from new-pay-accounts when available.
param(
  [string]$BaseUrl = "http://127.0.0.1:8081",
  [string]$LanBaseUrl = "",
  [string]$TokenFile = ".local/i343d1/sessions.local.json",
  [string]$PaymentTokenFile = ".local/i343d1/payment-accounts.local.json",
  [string]$ControlTokenFile = ".local/s32it/payment-control.token",
  [int]$OrderTtlSeconds = 120,
  [switch]$SkipExpiry
)

$ErrorActionPreference = "Stop"
$appCode = "calculator_vault"
$tokens = Get-Content $TokenFile -Raw | ConvertFrom-Json
$controlToken = (Get-Content $ControlTokenFile -Raw).Trim()
$results = New-Object System.Collections.Generic.List[object]

$roleTokens = @{}
$roleSource = "sessions.local.json pay-1..pay-3"
if (Test-Path $PaymentTokenFile) {
  $batch = Get-Content $PaymentTokenFile -Raw | ConvertFrom-Json
  if (@($batch.accounts).Count -ge 3) {
    for ($i = 0; $i -lt 3; $i++) { $roleTokens["pay-$($i + 1)"] = $batch.accounts[$i].accessToken }
    $roleSource = "payment-accounts batch $($batch.batch)"
  }
}

function Token([string]$label) {
  if ($roleTokens.ContainsKey($label)) { return $roleTokens[$label] }
  ($tokens.accounts | Where-Object label -eq $label | Select-Object -First 1).accessToken
}

function Invoke-Http([string]$method, [string]$url, [hashtable]$headers, [string]$body) {
  try {
    $args = @{ Method = $method; Uri = $url; Headers = $headers; UseBasicParsing = $true }
    if ($body) { $args.Body = [System.Text.Encoding]::UTF8.GetBytes($body); $args.ContentType = "application/json; charset=utf-8" }
    $r = Invoke-WebRequest @args
    $text = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    $json = $null; try { $json = $text | ConvertFrom-Json } catch {}
    return @{ http = [int]$r.StatusCode; json = $json; text = $text }
  } catch {
    $http = 0; $text = $null
    if ($_.ErrorDetails) { $text = $_.ErrorDetails.Message }
    if ($_.Exception.Response) {
      $http = [int]$_.Exception.Response.StatusCode
      if (-not $text) {
        try {
          $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream(), [System.Text.Encoding]::UTF8)
          $text = $sr.ReadToEnd()
        } catch {}
      }
    }
    $json = $null; try { $json = $text | ConvertFrom-Json } catch {}
    return @{ http = $http; json = $json; text = $text }
  }
}

function Api([string]$method, [string]$path, [string]$label, [string]$body) {
  $h = @{ "X-App-Code" = $appCode }
  if ($label) { $h["Authorization"] = "Bearer " + (Token $label) }
  Invoke-Http $method "$BaseUrl$path" $h $body
}

function Ctl([string]$method, [string]$path, [string]$base = $BaseUrl, [bool]$withToken = $true) {
  $h = @{}
  if ($withToken) { $h["X-Fixture-Control-Token"] = $controlToken }
  Invoke-Http $method "$base$path" $h $null
}

function Db([string]$sql) {
  $ErrorActionPreference = "Continue"
  (docker exec aurora-mysql-it mysql -utest -ptest -N -B aurora_app_test -e $sql 2>$null) -join ""
}

function Check([string]$name, [bool]$ok, [string]$detail) {
  $results.Add([pscustomobject]@{ check = $name; pass = $ok; detail = $detail })
}

function NewOrder([string]$label, [string]$plan) {
  $crid = [guid]::NewGuid().ToString()
  $r = Api POST "/api/v1/payment/orders" $label ("{""planCode"":""$plan"",""clientRequestId"":""$crid""}")
  return @{ r = $r; crid = $crid; orderNo = $r.json.data.orderNo }
}

function WaitOrder([string]$label, [string]$orderNo, [string]$status, [int]$seconds = 20) {
  $deadline = (Get-Date).AddSeconds($seconds)
  do {
    $g = Api GET "/api/v1/payment/orders/$orderNo" $label
    if ($g.json.data.status -eq $status) { return $g }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  return $g
}

function PaymentGrants([string]$orderNo) { [int](Db "SELECT COUNT(*) FROM membership_grants WHERE source_type='PAYMENT' AND source_ref='$orderNo'") }
function PaidEvents([string]$orderNo) { [int](Db "SELECT COUNT(*) FROM payment_order_events e JOIN payment_orders o ON o.id=e.order_id WHERE o.order_no='$orderNo' AND e.to_status='PAID' AND (e.from_status IS NULL OR e.from_status<>e.to_status)") }

# ---------------------------------------------------------------- environment
$h = Invoke-Http GET "$BaseUrl/actuator/health" @{} $null
Check "health" ($h.json.status -eq "UP") "status=$($h.json.status)"
Check "db=aurora_app_test" ((Db "SELECT DATABASE()") -eq "aurora_app_test") "read-only check on 3307"

# ---------------------------------------------------------------- plans
$p = Api GET "/api/v1/membership/plans" $null
$plans = $p.json.data
$planSummary = ($plans | ForEach-Object { "$($_.planCode):$($_.priceFen)/$($_.durationDays)" }) -join ","
Check "GET /membership/plans" ($p.http -eq 200 -and $plans.Count -eq 3 -and $planSummary -eq "MONTHLY:590/30,YEARLY:1990/365,LIFETIME:2990/") $planSummary

# ---------------------------------------------------------------- closure: notify path (pay-1 MONTHLY)
$me0 = Api GET "/api/v1/membership/me" "pay-1"
$o1 = NewOrder "pay-1" "MONTHLY"
$payValuePresent = [bool]$o1.r.json.data.pay.value
Check "POST /payment/orders (pay-1 MONTHLY)" ($o1.r.http -eq 200 -and $o1.r.json.data.status -eq "PENDING_PAYMENT" -and $o1.r.json.data.amountFen -eq 590 -and $o1.r.json.data.pay.kind -eq "TEST" -and -not $payValuePresent) "status=$($o1.r.json.data.status) amountFen=$($o1.r.json.data.amountFen) pay.kind=$($o1.r.json.data.pay.kind) pay.value present=$payValuePresent meBefore=$($me0.json.data.status) accounts=$roleSource"
$g1 = Api GET "/api/v1/payment/orders/$($o1.orderNo)" "pay-1"
Check "GET /payment/orders/{orderNo} pending" ($g1.json.data.status -eq "PENDING_PAYMENT") "status=$($g1.json.data.status)"

$b = Ctl POST "/internal/fixture/fake-payment/orders/$($o1.orderNo)/behavior?mode=PAID"
$n = Ctl POST "/internal/fixture/fake-payment/orders/$($o1.orderNo)/notify"
$f1 = WaitOrder "pay-1" $o1.orderNo "FULFILLED"
Check "simulate PAID + notify -> FULFILLED" ($b.http -eq 200 -and $n.json.ack -eq "success" -and $f1.json.data.status -eq "FULFILLED") "behavior=$($b.json.mode) ack=$($n.json.ack) status=$($f1.json.data.status) paidAt=$([bool]$f1.json.data.paidAt)"
$me1 = Api GET "/api/v1/membership/me" "pay-1"
$grantsBefore = [int]$me0.json.data.activeGrantCount
Check "GET /membership/me VIP (pay-1)" ($me1.json.data.status -eq "ACTIVE" -and $me1.json.data.planType -eq "MONTHLY" -and [int]$me1.json.data.activeGrantCount -eq ($grantsBefore + 1)) "status=$($me1.json.data.status) planType=$($me1.json.data.planType) expiresAt=$($me1.json.data.membershipExpiresAt) grants=$grantsBefore->$($me1.json.data.activeGrantCount)"

# ---------------------------------------------------------------- idempotency
$acks = @(); 1..3 | ForEach-Object { $acks += (Ctl POST "/internal/fixture/fake-payment/orders/$($o1.orderNo)/notify").json.ack }
1..5 | ForEach-Object { $null = Api GET "/api/v1/payment/orders/$($o1.orderNo)" "pay-1" }
1..2 | ForEach-Object { $null = Ctl POST "/internal/fixture/payment-jobs/run" }
$replay = Api POST "/api/v1/payment/orders" "pay-1" ("{""planCode"":""MONTHLY"",""clientRequestId"":""$($o1.crid)""}")
$me2 = Api GET "/api/v1/membership/me" "pay-1"
Check "duplicate notify x3 / query x5 / jobs x2" (($acks | Where-Object { $_ -ne "success" }).Count -eq 0 -and (PaymentGrants $o1.orderNo) -eq 1 -and (PaidEvents $o1.orderNo) -eq 1) "acks=$($acks -join '/') paymentGrants=$(PaymentGrants $o1.orderNo) paidTransitions=$(PaidEvents $o1.orderNo)"
Check "membership idempotent" ($me2.json.data.membershipExpiresAt -eq $me1.json.data.membershipExpiresAt -and $me2.json.data.activeGrantCount -eq $me1.json.data.activeGrantCount) "expiresAt unchanged=$($me2.json.data.membershipExpiresAt -eq $me1.json.data.membershipExpiresAt) grants unchanged=$($me2.json.data.activeGrantCount -eq $me1.json.data.activeGrantCount)"
Check "same clientRequestId replay" ($replay.json.data.orderNo -eq $o1.orderNo -and $replay.json.data.status -eq "FULFILLED" -and -not $replay.json.data.pay) "status=$($replay.json.data.status) sameOrder=$($replay.json.data.orderNo -eq $o1.orderNo)"

# ---------------------------------------------------------------- closure: query-only path (pay-2 YEARLY)
$o2 = NewOrder "pay-2" "YEARLY"
$null = Ctl POST "/internal/fixture/fake-payment/orders/$($o2.orderNo)/behavior?mode=PAID"
$job = Ctl POST "/internal/fixture/payment-jobs/run"
$f2 = WaitOrder "pay-2" $o2.orderNo "FULFILLED"
$me3 = Api GET "/api/v1/membership/me" "pay-2"
Check "server query (no notify) -> FULFILLED (pay-2 YEARLY)" ($f2.json.data.status -eq "FULFILLED" -and $me3.json.data.planType -eq "YEARLY" -and (PaymentGrants $o2.orderNo) -eq 1) "status=$($f2.json.data.status) me=$($me3.json.data.status)/$($me3.json.data.planType)"

# ---------------------------------------------------------------- isolation & access control
$cross = Api GET "/api/v1/payment/orders/$($o1.orderNo)" "pay-2"
Check "other user's order hidden" ($cross.http -eq 404 -and $cross.json.code -eq 40403) "http=$($cross.http) code=$($cross.json.code)"
$anon = Api POST "/api/v1/payment/orders" $null '{"planCode":"MONTHLY","clientRequestId":"anon-check-0001"}'
Check "create order without login" ($anon.http -eq 401) "http=$($anon.http)"
$noTok = Ctl POST "/internal/fixture/payment-jobs/run" $BaseUrl $false
Check "control without token" ($noTok.http -eq 401) "http=$($noTok.http)"
if ($LanBaseUrl) {
  $lan = Ctl POST "/internal/fixture/payment-jobs/run" $LanBaseUrl $true
  Check "control from non-loopback address" ($lan.http -eq 403) "http=$($lan.http) via $LanBaseUrl"
}
$yusen = Invoke-Http GET "$BaseUrl/api/v1/payment/notify/yusen?out_trade_no=$($o1.orderNo)&trade_status=TRADE_SUCCESS" @{} $null
Check "real Yusen notify disabled in s32it" ($yusen.text -eq "fail") "body=$($yusen.text)"

# ---------------------------------------------------------------- timeout, inconclusive, late payment (pay-3 MONTHLY)
$o3 = @{ orderNo = "(skipped)" }
if (-not $SkipExpiry) {
$o3 = NewOrder "pay-3" "MONTHLY"
$null = Ctl POST "/internal/fixture/fake-payment/orders/$($o3.orderNo)/behavior?mode=TIMEOUT"
$null = Ctl POST "/internal/fixture/payment-jobs/run"
$t1 = Api GET "/api/v1/payment/orders/$($o3.orderNo)" "pay-3"
Check "query TIMEOUT is not a failure" ($t1.json.data.status -eq "PENDING_PAYMENT" -and (Db "SELECT status FROM payment_orders WHERE order_no='$($o3.orderNo)'") -eq "PENDING_PAYMENT") "status=$($t1.json.data.status)"

$null = Ctl POST "/internal/fixture/fake-payment/orders/$($o3.orderNo)/behavior?mode=UNPAID"
Start-Sleep -Seconds ($OrderTtlSeconds + 25)
$t2 = Api GET "/api/v1/payment/orders/$($o3.orderNo)" "pay-3"
$null = Ctl POST "/internal/fixture/payment-jobs/run"
$dbExpired = Db "SELECT status FROM payment_orders WHERE order_no='$($o3.orderNo)'"
Check "order expiry (view + persisted)" ($t2.json.data.status -eq "EXPIRED" -and -not $t2.json.data.pay -and $dbExpired -eq "EXPIRED") "api=$($t2.json.data.status) db=$dbExpired"

$null = Ctl POST "/internal/fixture/fake-payment/orders/$($o3.orderNo)/behavior?mode=PAID"
$late = Ctl POST "/internal/fixture/fake-payment/orders/$($o3.orderNo)/notify"
$f3 = WaitOrder "pay-3" $o3.orderNo "FULFILLED"
$lateRow = Db "SELECT CONCAT(late_paid,'/',COALESCE(anomaly_code,'-')) FROM payment_orders WHERE order_no='$($o3.orderNo)'"
$me4 = Api GET "/api/v1/membership/me" "pay-3"
Check "late payment granted + flagged" ($f3.json.data.status -eq "FULFILLED" -and $lateRow -eq "1/LATE_PAYMENT" -and $me4.json.data.status -eq "ACTIVE" -and (PaymentGrants $o3.orderNo) -eq 1) "ack=$($late.json.ack) late_paid/anomaly=$lateRow me=$($me4.json.data.status)"
}

$results | Format-Table -AutoSize -Wrap | Out-String -Width 220 | Write-Output
$failed = @($results | Where-Object { -not $_.pass }).Count
"PASS=$($results.Count - $failed) FAIL=$failed"
"orders: pay-1=$($o1.orderNo) pay-2=$($o2.orderNo) pay-3=$($o3.orderNo)"
if ($failed -gt 0) { exit 1 }
