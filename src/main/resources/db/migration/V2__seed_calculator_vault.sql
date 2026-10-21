-- Seed first Android app: 隐藏计算器 (calculator_vault)
-- Do not hard-code numeric app_id in application code; always resolve via app_code.
INSERT INTO apps (app_code, app_name, platform, package_name, status)
VALUES ('calculator_vault', '隐藏计算器', 'ANDROID', 'com.aurora.calculatorvault', 'ACTIVE');
