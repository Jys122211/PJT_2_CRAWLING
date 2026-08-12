package org.example.kbloan.model;

/**
 * 상세페이지를 어떤 경로로 얻었는지.
 * DB 에는 저장하지 않는다. 실행 로그에서 어느 경로로 긁었는지 보려는 용도다.
 * 같은 금리라도 어느 경로로 얻었는지에 따라 신뢰도가 다르다.
 */
public enum CollectMode {
    /** 목록 페이지를 파싱해 대상 상품을 찾아냈다. */
    LIST_DISCOVERY,

    /** kb.detailUrl 로 지정된 상세 URL 하나만 봤다. 목록 탐색을 건너뛴다. */
    DIRECT_URL,

    /** 목록에서 대상을 찾지 못해 KnownProductCatalog 로 대체했다. */
    FALLBACK_CATALOG
}
