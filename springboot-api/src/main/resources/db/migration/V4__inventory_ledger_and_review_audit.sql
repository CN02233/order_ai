-- Every stock change must be attributable to a business event.
-- The ledger is append-only: it records what the service layer actually did.
CREATE TABLE stock_movements (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id BIGINT NOT NULL,
  sku_snapshot VARCHAR(80) NOT NULL,
  delta INT NOT NULL,
  balance_after INT NOT NULL,
  movement_type VARCHAR(40) NOT NULL,
  reference VARCHAR(120) NULL,
  note VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_stock_movement_product FOREIGN KEY (product_id) REFERENCES products(id),
  CONSTRAINT chk_stock_movement_delta CHECK (delta <> 0),
  CONSTRAINT chk_stock_movement_balance CHECK (balance_after >= 0)
);

CREATE INDEX idx_stock_movements_product ON stock_movements(product_id, id);
CREATE INDEX idx_stock_movements_reference ON stock_movements(reference);

-- Audit trail for manual approval decisions.
ALTER TABLE refund_requests
  ADD COLUMN reviewed_at TIMESTAMP NULL;

CREATE INDEX idx_refund_requests_status ON refund_requests(status);
CREATE INDEX idx_order_items_product ON order_items(product_id);
