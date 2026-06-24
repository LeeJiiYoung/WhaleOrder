# Menu — 메뉴

> 매장별 메뉴/옵션/할인 관리. 조회 성능을 위해 Redis 캐시 적용.

**디렉토리**: `src/main/java/com/whale/order/domain/menu/`

## 구성

| 분류 | 파일 |
|------|------|
| Entity | `Menu`, `MenuCategory`, `MenuOption` (옵션 그룹 단위 필수 여부 `isRequired`), `MenuDiscount` |
| Controller | `AdminMenuController` |
| Service | `MenuService` |
| Repository | `MenuRepository`, `MenuOptionRepository` |

## 옵션 그룹 필수 여부

- `MenuOption.isRequired` (DB 컬럼: `menu_option.is_required BOOLEAN NOT NULL DEFAULT false`)
- **운영 규칙**: 같은 `menu_id + option_group` 의 모든 행은 **동일한 `isRequired` 값을 유지**해야 한다. 그룹 단위 의미이지만 저장은 행 단위라서 강제는 관리자 UI 책임.
- 시드 정책: `SIZE`, `TEMPERATURE` 그룹은 `true` (사이즈·온도는 반드시 선택). `SHOT`, `SYRUP` 은 `false`.
- 검증 시점: `CartService.addItem` 에서 메뉴의 필수 그룹이 `selectedOptions` 에 모두 포함됐는지 확인. 누락 시 `IllegalArgumentException("다음 옵션은 필수로 선택해야 합니다: ...")`.

## 캐시 전략

- 캐시명: `menus` (목록), `menu` (상세)
- 어노테이션: `@Cacheable` + `@CacheEvict` (변경 시)
- 이유: 메뉴 조회는 압도적 읽기 우위, DB 부하 감소

## 의존 관계

- `Store` 에 속함 (N:1)
- `Stock` 은 메뉴별로 관리 (`stock:lock:{storeId}:{menuId}`)
- `OrderItem` 이 `Menu` 참조

## 관련 문서

- [Redis 활용처 — 메뉴 캐시](../architecture/redis-usage.md#4-메뉴-캐시-cacheable)
