param([Parameter(Mandatory=$true)][string]$Email)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
$sqlText = @("SELECT EXISTS(SELECT 1 FROM accounts WHERE email=lower(trim(:'target_email'))) AS account_found \gset", "\if :account_found", "INSERT INTO platform_administrators(account_id) SELECT id FROM accounts WHERE email=lower(trim(:'target_email')) ON CONFLICT DO NOTHING;", "SELECT 'PLATFORM_ADMIN_PROVISIONED';", "\else", "\echo ACCOUNT_NOT_FOUND", 'DO $$ BEGIN RAISE EXCEPTION ''Account must be registered before provisioning''; END $$;', "\endif") -join [Environment]::NewLine
$sqlText | docker compose exec -T postgres psql -U helpdesk -d helpdesk -v ON_ERROR_STOP=1 -v "target_email=$Email"
if($LASTEXITCODE -ne 0){throw 'Provisioning failed.'}
} finally { Pop-Location }
