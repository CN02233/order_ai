-- Purges the rows written by smoke/smoke_test.py.
--
-- Development databases only. This deletes by name prefix, so pointing it at a
-- database that contains real orders named "ORD-SMOKE-*" or products named
-- "SMOKE-*" would destroy them.
--
--   mysql -h127.0.0.1 -uroot -p order_ai < smoke/cleanup.sql
--
-- The test cannot clean up after itself through the API because two rules make
-- the data immortal on purpose: an approved refund is retained for audit, and a
-- product with stock movements keeps its ledger. This script is the escape hatch
-- for repeated runs against the same development database.

SET @order_prefix = 'ORD-SMOKE-%';
SET @sku_prefix = 'SMOKE-%';
SET @email_prefix = 'smoke+%';

START TRANSACTION;

-- Parcels, children before the order that owns them.
DELETE se
  FROM shipment_events se
  JOIN shipments s ON s.id = se.shipment_id
  JOIN orders o ON o.id = s.order_id
 WHERE o.order_number LIKE @order_prefix;

DELETE si
  FROM shipment_items si
  JOIN shipments s ON s.id = si.shipment_id
  JOIN orders o ON o.id = s.order_id
 WHERE o.order_number LIKE @order_prefix;

DELETE s
  FROM shipments s
  JOIN orders o ON o.id = s.order_id
 WHERE o.order_number LIKE @order_prefix;

-- Refunds and their return lines.
DELETE rri
  FROM refund_return_items rri
  JOIN refund_requests r ON r.id = rri.refund_request_id
 WHERE r.order_number LIKE @order_prefix;

DELETE FROM refund_requests WHERE order_number LIKE @order_prefix;

-- Order lines, then the orders, then the customers only they used.
DELETE oi
  FROM order_items oi
  JOIN orders o ON o.id = oi.order_id
 WHERE o.order_number LIKE @order_prefix;

DELETE FROM orders WHERE order_number LIKE @order_prefix;

DELETE FROM customers WHERE email LIKE @email_prefix;

-- The ledger must go before the products it explains.
DELETE FROM stock_movements WHERE sku_snapshot LIKE @sku_prefix;

DELETE FROM products WHERE sku LIKE @sku_prefix;

COMMIT;
