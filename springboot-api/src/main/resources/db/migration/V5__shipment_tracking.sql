-- Logistics: a parcel is the unit the carrier and the customer track, and one order may need several.
-- Stock is NOT touched here: it was already reserved when the order was created, so shipping only
-- records which units left the warehouse.
CREATE TABLE shipments (
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
  CONSTRAINT fk_shipments_order FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT chk_shipments_status CHECK (status IN ('CREATED','PICKED_UP','IN_TRANSIT','DELIVERED','EXCEPTION','CANCELLED'))
);

CREATE INDEX idx_shipments_order ON shipments(order_id);
CREATE INDEX idx_shipments_status ON shipments(status);

-- Explicit per-line split so partial shipping can be validated against the ordered quantity.
CREATE TABLE shipment_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  shipment_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  CONSTRAINT uq_shipment_items_line UNIQUE (shipment_id, order_item_id),
  CONSTRAINT fk_shipment_items_shipment FOREIGN KEY (shipment_id) REFERENCES shipments(id),
  CONSTRAINT fk_shipment_items_order_item FOREIGN KEY (order_item_id) REFERENCES order_items(id),
  CONSTRAINT chk_shipment_items_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_shipment_items_line ON shipment_items(order_item_id);
CREATE INDEX idx_shipment_items_shipment ON shipment_items(shipment_id);

-- Append-only tracking timeline, written in the same transaction as the status change.
CREATE TABLE shipment_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  shipment_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  location VARCHAR(200) NULL,
  description VARCHAR(500) NULL,
  occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_shipment_events_shipment FOREIGN KEY (shipment_id) REFERENCES shipments(id)
);

CREATE INDEX idx_shipment_events_shipment ON shipment_events(shipment_id, occurred_at);

-- Orders that already carry a free-text tracking number become one parcel each, so the history the
-- workbench showed before this feature stays visible in the new module.
INSERT INTO shipments (order_id, order_number, carrier, tracking_number, status, shipped_at, delivered_at)
SELECT o.id,
       o.order_number,
       'UNKNOWN',
       o.tracking_number,
       CASE o.status
         WHEN 'DELIVERED' THEN 'DELIVERED'
         WHEN 'SHIPPED' THEN 'IN_TRANSIT'
         ELSE 'CREATED'
       END,
       o.created_at,
       CASE WHEN o.status = 'DELIVERED' THEN o.created_at ELSE NULL END
FROM orders o
WHERE o.tracking_number IS NOT NULL
  AND TRIM(o.tracking_number) <> '';
