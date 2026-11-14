"""Idempotently add ten demo customers, products and orders."""
from app.db import Base, engine, SessionLocal
from app.models import Customer, Product, Order, OrderItem

Base.metadata.create_all(bind=engine)

CUSTOMERS = [
    ("john@example.com", "John Smith"), ("alice@example.com", "Alice Chen"),
    ("bob@example.com", "Bob Lee"), ("maria@example.com", "Maria Garcia"),
    ("david@example.com", "David Kim"), ("emma@example.com", "Emma Wilson"),
    ("liam@example.com", "Liam Brown"), ("sophia@example.com", "Sophia Davis"),
    ("noah@example.com", "Noah Miller"), ("olivia@example.com", "Olivia Taylor"),
]
PRODUCTS = [
    ("MUG-001", "Ceramic Mug", 24, 12.99), ("BAG-002", "Canvas Tote Bag", 12, 18.50),
    ("BOT-003", "Steel Water Bottle", 0, 25.00), ("TEE-004", "Cotton T-Shirt", 38, 22.00),
    ("NOTE-005", "Notebook Set", 16, 9.75),
]
ORDERS = [
    ("ORD-10001", "john@example.com", "SHIPPED", "TRACK-ABC123", "MUG-001", 2),
    ("ORD-10002", "alice@example.com", "PROCESSING", None, "BAG-002", 1),
    ("ORD-10003", "bob@example.com", "DELIVERED", "TRACK-ABC124", "BOT-003", 1),
    ("ORD-10004", "maria@example.com", "SHIPPED", "TRACK-ABC125", "TEE-004", 2),
    ("ORD-10005", "david@example.com", "DELIVERED", "TRACK-ABC126", "NOTE-005", 3),
    ("ORD-10006", "emma@example.com", "CANCELLED", None, "MUG-001", 1),
    ("ORD-10007", "liam@example.com", "SHIPPED", "TRACK-ABC127", "BAG-002", 2),
    ("ORD-10008", "sophia@example.com", "DELIVERED", "TRACK-ABC128", "TEE-004", 1),
    ("ORD-10009", "noah@example.com", "PROCESSING", None, "NOTE-005", 2),
    ("ORD-10010", "olivia@example.com", "SHIPPED", "TRACK-ABC129", "MUG-001", 1),
]

def seed():
    with SessionLocal() as db:
        for email, name in CUSTOMERS:
            if not db.query(Customer).filter_by(email=email).first():
                db.add(Customer(email=email, name=name))
        for sku, name, stock, price in PRODUCTS:
            if not db.query(Product).filter_by(sku=sku).first():
                db.add(Product(sku=sku, name=name, stock=stock, price=price))
        db.flush()
        for number, email, status, tracking, sku, qty in ORDERS:
            if db.query(Order).filter_by(order_number=number).first():
                continue
            customer = db.query(Customer).filter_by(email=email).one()
            product = db.query(Product).filter_by(sku=sku).one()
            order = Order(order_number=number, customer_id=customer.id, status=status, tracking_number=tracking)
            db.add(order); db.flush()
            db.add(OrderItem(order_id=order.id, product_id=product.id, quantity=qty, unit_price=product.price))
        db.commit()

if __name__ == "__main__":
    seed()
