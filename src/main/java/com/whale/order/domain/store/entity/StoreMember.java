package com.whale.order.domain.store.entity;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매장-직원 관계 Entity.
 * 한 직원이 여러 매장에 소속될 수 있으며, 매장마다 다른 역할을 가질 수 있다.
 * 점주(OWNER)는 이 테이블이 아닌 Store.owner로 관리한다.
 */
@Entity
@Table(
    name = "store_member",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_store_member",
        columnNames = {"store_id", "member_id"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreMember extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long storeMemberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    // 매장 내 역할 (BARISTA / STORE_ADMIN)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StoreMemberRole role;

    @Builder
    public StoreMember(Store store, Member member, StoreMemberRole role) {
        this.store = store;
        this.member = member;
        this.role = role;
    }

    public void updateRole(StoreMemberRole role) {
        this.role = role;
    }
}
