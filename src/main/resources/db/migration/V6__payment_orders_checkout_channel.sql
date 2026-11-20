-- Requested checkout channel for Yusen submit.php type=wxpay|alipay.
-- Nullable for historical orders created before channel selection.
ALTER TABLE payment_orders
    ADD COLUMN checkout_channel VARCHAR(32) NULL AFTER merchant_pid;

ALTER TABLE payment_orders
    ADD CONSTRAINT chk_payment_orders_checkout_channel CHECK (
        checkout_channel IS NULL OR checkout_channel IN ('WECHAT_PAY', 'ALIPAY')
    );
