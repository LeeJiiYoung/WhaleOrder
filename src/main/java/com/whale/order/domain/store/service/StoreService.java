package com.whale.order.domain.store.service;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.store.dto.StoreCreateRequest;
import com.whale.order.domain.store.dto.StoreResponse;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public List<StoreResponse> getStores() {
        return storeRepository.findAllWithOwner().stream()
                .map(StoreResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public StoreResponse getStore(Long storeId) {
        Store store = storeRepository.findByIdWithOwner(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다: " + storeId));
        return StoreResponse.from(store);
    }

    @Transactional
    public StoreResponse openStore(Long storeId) {
        Store store = storeRepository.findByIdWithOwner(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다: " + storeId));
        store.open();
        return StoreResponse.from(store);
    }

    @Transactional
    public StoreResponse closeStore(Long storeId) {
        Store store = storeRepository.findByIdWithOwner(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다: " + storeId));
        store.close();
        return StoreResponse.from(store);
    }

    @Transactional
    public StoreResponse createStore(StoreCreateRequest request) {
        Member owner = memberRepository.findByUserId(request.ownerUserId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다: " + request.ownerUserId()));

        Store store = Store.builder()
                .owner(owner)
                .name(request.name())
                .postalCode(request.postalCode())
                .address(request.address())
                .addressDetail(request.addressDetail())
                .phone(request.phone())
                .openTime(request.openTime())
                .closeTime(request.closeTime())
                .build();

        storeRepository.save(store);
        return StoreResponse.from(store);
    }
}
