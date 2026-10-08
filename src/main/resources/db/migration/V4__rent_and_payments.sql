-- V1 created a basic payments table (amount > 0, PAID/PENDING, CASH/UPI/BANK_TRANSFER, no cascading deletes).
-- Adjust it for rent tracking instead of creating a second table.

ALTER TABLE payments RENAME COLUMN month TO rent_month;
ALTER TABLE payments RENAME CONSTRAINT payments_month_check TO payments_rent_month_check;

-- Optional reference such as a UPI transaction ID or bank reference number
ALTER TABLE payments ADD COLUMN receipt_id VARCHAR(100);
ALTER TABLE payments ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Duplicate protection. Several payments for the same month ARE allowed (rent paid in installments),
-- but these two cases are always mistakes:
--   1. the same receipt (UPI/bank reference) recorded twice
--   2. two PENDING (unpaid) records for the same tenant and month
CREATE UNIQUE INDEX uq_payments_receipt ON payments (receipt_id) WHERE receipt_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payments_pending_month ON payments (tenant_id, rent_month) WHERE status = 'PENDING';

CREATE INDEX idx_payments_rent_month ON payments (rent_month);
