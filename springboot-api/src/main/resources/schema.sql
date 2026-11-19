-- Full schema for a brand-new database.
-- Existing databases get the same state by applying V2 -> V3 -> V4 -> V5 -> V6 from db/migration/.
CREATE TABLE IF NOT EXISTS customers(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(255) UNIQUE NOT NULL,
  name VARCHAR(200) NOT NULL
);

CREATE TABLE IF NOT EXISTS products(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku VARCHAR(80) UNIQUE NOT NULL,
  name VARCHAR(200) NOT NULL,
  stock INT NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  CONSTRAINT chk_products_stock CHECK (stock >= 0),
  CONSTRAINT chk_products_price CHECK (price >= 0)
);

CREATE TABLE IF NOT EXISTS orders(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_number VARCHAR(80) UNIQUE NOT NULL,
  customer_id BIGINT NOT NULL,
  status VARCHAR(40) NOT NULL,
  tracking_number VARCHAR(100),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  total_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  -- TRUE only when this order actually decremented stock, so legacy orders never release stock they never took.
  stock_reserved BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT fk_orders_customer FOREIGN KEY(customer_id) REFERENCES customers(id)
);

-- unit_price is the price snapshot taken at checkout; product price changes must never rewrite history.
CREATE TABLE IF NOT EXISTS order_items(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  sku_snapshot VARCHAR(80) NOT NULL,
  product_name_snapshot VARCHAR(200) NOT NULL,
  quantity INT NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL,
  CONSTRAINT fk_order_items_order FOREIGN KEY(order_id) REFERENCES orders(id),
  CONSTRAINT fk_order_items_product FOREIGN KEY(product_id) REFERENCES products(id),
  CONSTRAINT chk_order_items_quantity CHECK (quantity > 0),
  CONSTRAINT chk_order_items_price CHECK (unit_price >= 0)
);

-- A parcel is the unit the carrier tracks; one order may ship in several parcels.
CREATE TABLE IF NOT EXISTS shipments(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  order_number VARCHAR(80) NOT NULL,
  carrier VARCHAR(80) NOT NULL,
  tracking_number VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  shipped_at TIMESTAMP NULL,
  estimated_delivery DATE NULL,
  delivered_at TIMESTAMP NULL,
  receiver_name VARCHAR(120) NULL,
  note VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uq_shipments_tracking UNIQUE (tracking_number),
  CONSTRAINT fk_shipments_order FOREIGN KEY(order_id) REFERENCES orders(id),
  CONSTRAINT chk_shipments_status CHECK (status IN ('CREATED','PICKED_UP','IN_TRANSIT','DELIVERED','EXCEPTION','CANCELLED'))
);

-- Which order line and how many units travel in which parcel.
CREATE TABLE IF NOT EXISTS shipment_items(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  shipment_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  CONSTRAINT uq_shipment_items_line UNIQUE (shipment_id, order_item_id),
  CONSTRAINT fk_shipment_items_shipment FOREIGN KEY(shipment_id) REFERENCES shipments(id),
  CONSTRAINT fk_shipment_items_order_item FOREIGN KEY(order_item_id) REFERENCES order_items(id),
  CONSTRAINT chk_shipment_items_quantity CHECK (quantity > 0)
);

CREATE TABLE IF NOT EXISTS shipment_events(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  shipment_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  location VARCHAR(200) NULL,
  description VARCHAR(500) NULL,
  occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_shipment_events_shipment FOREIGN KEY(shipment_id) REFERENCES shipments(id)
);

CREATE TABLE IF NOT EXISTS inquiries(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  idempotency_key VARCHAR(128) UNIQUE NOT NULL,
  customer_email VARCHAR(255),
  subject VARCHAR(500),
  body TEXT,
  category VARCHAR(40),
  -- Complete AI analysis result; nullable so rows created before V6 stay readable.
  confidence DECIMAL(5,4) NULL,
  needs_human_review BOOLEAN NULL,
  suggested_reply TEXT NULL,
  analysis_source VARCHAR(20) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS refund_requests(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  idempotency_key VARCHAR(128) UNIQUE NOT NULL,
  order_number VARCHAR(80) NOT NULL,
  reason TEXT,
  amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  refund_type VARCHAR(32) NOT NULL DEFAULT 'REFUND_ONLY',
  inventory_received BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(40) NOT NULL DEFAULT 'PENDING_MANUAL_APPROVAL',
  reviewed_at TIMESTAMP NULL,
  created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_refund_requests_order FOREIGN KEY(order_number) REFERENCES orders(order_number),
  CONSTRAINT chk_refund_requests_amount CHECK (amount > 0),
  CONSTRAINT chk_refund_requests_type CHECK (refund_type IN ('REFUND_ONLY','RETURN_AND_REFUND'))
);

CREATE TABLE IF NOT EXISTS refund_return_items(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  refund_request_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  accepted_quantity INT NOT NULL DEFAULT 0,
  received_at TIMESTAMP NULL,
  CONSTRAINT uq_refund_return_line UNIQUE (refund_request_id, order_item_id),
  CONSTRAINT fk_refund_return_refund FOREIGN KEY(refund_request_id) REFERENCES refund_requests(id),
  CONSTRAINT fk_refund_return_order_item FOREIGN KEY(order_item_id) REFERENCES order_items(id),
  CONSTRAINT chk_refund_return_quantity CHECK (quantity > 0),
  CONSTRAINT chk_refund_return_accepted CHECK (accepted_quantity >= 0 AND accepted_quantity <= quantity)
);

-- Append-only ledger. Only the service layer writes here, always in the same transaction as the stock update.
CREATE TABLE IF NOT EXISTS stock_movements(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id BIGINT NOT NULL,
  sku_snapshot VARCHAR(80) NOT NULL,
  delta INT NOT NULL,
  balance_after INT NOT NULL,
  movement_type VARCHAR(40) NOT NULL,
  reference VARCHAR(120),
  note VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_stock_movement_product FOREIGN KEY(product_id) REFERENCES products(id),
  CONSTRAINT chk_stock_movement_delta CHECK (delta <> 0),
  CONSTRAINT chk_stock_movement_balance CHECK (balance_after >= 0)
);

CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_order_items_product ON order_items(product_id);
CREATE INDEX idx_refund_requests_order ON refund_requests(order_number);
CREATE INDEX idx_refund_requests_status ON refund_requests(status);
CREATE INDEX idx_stock_movements_product ON stock_movements(product_id, id);
CREATE INDEX idx_stock_movements_reference ON stock_movements(reference);
CREATE INDEX idx_shipments_order ON shipments(order_id);
CREATE INDEX idx_shipments_status ON shipments(status);
CREATE INDEX idx_shipment_items_line ON shipment_items(order_item_id);
CREATE INDEX idx_shipment_items_shipment ON shipment_items(shipment_id);
CREATE INDEX idx_shipment_events_shipment ON shipment_events(shipment_id, occurred_at);
