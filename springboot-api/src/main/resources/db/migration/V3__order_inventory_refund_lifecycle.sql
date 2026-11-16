-- Legacy tables were created with INT keys and inline foreign keys while every new table uses BIGINT.
-- Widen the legacy keys first (foreign keys must be dropped before the columns can change) so the
-- new tables can reference them and so a migrated database matches schema.sql.
ALTER TABLE order_items DROP FOREIGN KEY order_items_ibfk_1, DROP FOREIGN KEY order_items_ibfk_2;
ALTER TABLE orders DROP FOREIGN KEY orders_ibfk_1;
ALTER TABLE refund_requests DROP FOREIGN KEY refund_requests_ibfk_1;

ALTER TABLE customers MODIFY id BIGINT AUTO_INCREMENT;
ALTER TABLE products MODIFY id BIGINT AUTO_INCREMENT;
ALTER TABLE orders MODIFY id BIGINT AUTO_INCREMENT, MODIFY customer_id BIGINT NOT NULL;
ALTER TABLE order_items MODIFY id BIGINT AUTO_INCREMENT, MODIFY order_id BIGINT NOT NULL, MODIFY product_id BIGINT NOT NULL;
ALTER TABLE refund_requests MODIFY id BIGINT AUTO_INCREMENT;
ALTER TABLE inquiries MODIFY id BIGINT AUTO_INCREMENT;

ALTER TABLE orders ADD CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id);
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id);
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id);
ALTER TABLE refund_requests ADD CONSTRAINT fk_refund_requests_order FOREIGN KEY (order_number) REFERENCES orders(order_number);

-- Preserve historical orders without adjusting stock: the old workflow did not reserve it.
ALTER TABLE orders
  ADD COLUMN total_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  ADD COLUMN stock_reserved BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE order_items
  ADD COLUMN sku_snapshot VARCHAR(80) NULL,
  ADD COLUMN product_name_snapshot VARCHAR(200) NULL;

UPDATE order_items oi
LEFT JOIN products p ON p.id = oi.product_id
SET oi.sku_snapshot = COALESCE(p.sku, CONCAT('LEGACY-', oi.product_id)),
    oi.product_name_snapshot = COALESCE(p.name, 'Legacy product')
WHERE oi.sku_snapshot IS NULL OR oi.product_name_snapshot IS NULL;

UPDATE orders o
LEFT JOIN (
  SELECT order_id, SUM(quantity * unit_price) AS total_amount
  FROM order_items
  GROUP BY order_id
) totals ON totals.order_id = o.id
SET o.total_amount = COALESCE(totals.total_amount, 0.00);

ALTER TABLE order_items
  MODIFY COLUMN sku_snapshot VARCHAR(80) NOT NULL,
  MODIFY COLUMN product_name_snapshot VARCHAR(200) NOT NULL;

ALTER TABLE refund_requests
  ADD COLUMN amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  ADD COLUMN refund_type VARCHAR(32) NOT NULL DEFAULT 'REFUND_ONLY',
  ADD COLUMN inventory_received BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE refund_return_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  refund_request_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  accepted_quantity INT NOT NULL DEFAULT 0,
  received_at TIMESTAMP NULL,
  CONSTRAINT uq_refund_return_line UNIQUE (refund_request_id, order_item_id),
  CONSTRAINT fk_refund_return_refund FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id),
  CONSTRAINT fk_refund_return_order_item FOREIGN KEY (order_item_id) REFERENCES order_items(id),
  CONSTRAINT chk_refund_return_quantity CHECK (quantity > 0),
  CONSTRAINT chk_refund_return_accepted CHECK (accepted_quantity >= 0 AND accepted_quantity <= quantity)
);

CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_refund_requests_order ON refund_requests(order_number);
