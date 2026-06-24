# ERD

> WhaleOrder DB 엔티티 관계도

![ERD](../assets/erd.png)

## 핵심 테이블

| 테이블 | 도메인 문서 |
|--------|-------------|
| `members` | [Member](../domains/member.md) |
| `stores` | [Store](../domains/store.md) |
| `menus` / `menu_options` / `menu_discounts` / `menu_categories` | [Menu](../domains/menu.md) |
| `stocks` / `stock_restore_failures` | [Stock](../domains/stock.md) |
| `orders` / `order_items` / `order_status_history` | [Order](../domains/order.md) |
| `payments` / `payment_history` | [Payment](../domains/payment.md) |
| `events` / `event_purchases` / `goods` | [Event](../domains/event.md) |

## 주요 관계

```
Member ──1:N── Order ──1:N── OrderItem ──N:1── Menu
                  │                            │
                  └─1:1── Payment              └── Stock (Store×Menu)
                            └─1:N── PaymentHistory

Store ──1:N── Menu
   └──1:N── Order

Event ──1:N── EventPurchase
   └──N:1── Goods
```

## 변경 이력

- 다이어그램 원본: `docs/wiki/assets/erd.png`
- 도메인 엔티티 변경 시 위 그림 재export 필요
