package co.onecheck.ones1ght.android.model

/**
 * 건물 — 목록 UI 용. 층 목록은 담지 않는다(필요하면 따로 받는다 — 층마다 도면 조회가
 * 따라붙어 목록이 무거워지기 때문).
 *
 * 포팅 원본: SpaceModels.swift 의 `Building`.
 */
public data class Building @JvmOverloads constructor(
    public val id: String,
    public val name: String,
    /** 층 개수. 서버가 아직 이 값을 주지 않으면 null — 그때는 표시를 생략해야 한다. */
    public val floorCount: Int? = null,
)

/**
 * 층 — 선택 UI + 지도 배경.
 *
 * ⚠️ [image] 는 목록 조회에서 비어 있다(null). 단건 조회에서만 채워져 온다.
 * [equals]·[hashCode] 는 [image] 를 참조가 아니라 바이트 내용으로 비교한다.
 *
 * 포팅 원본: SpaceModels.swift 의 `Floor`.
 */
public data class Floor @JvmOverloads constructor(
    public val id: String,
    public val name: String,
    /** 도면 PNG. 목록 조회에서는 null. */
    public val image: ByteArray? = null,
    /** 도면이 등록된 층인가 — [image] 가 null 이어도 이 값으로 판단할 수 있다. */
    public val hasPlan: Boolean = false,
    /** 도면 원점 오프셋 (미터) */
    public val originX: Double = 0.0,
    public val originY: Double = 0.0,
    /** 도면 실제 크기 (미터) */
    public val widthM: Double = 0.0,
    public val heightM: Double = 0.0,
) {
    /** 도면이 덮는 범위 — 지도 배치(bounds)에 그대로 쓴다. */
    public val minX: Double get() = originX
    public val minY: Double get() = originY
    public val maxX: Double get() = originX + widthM
    public val maxY: Double get() = originY + heightM

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Floor) return false
        return id == other.id &&
            name == other.name &&
            (image?.contentEquals(other.image) ?: (other.image == null)) &&
            hasPlan == other.hasPlan &&
            originX == other.originX &&
            originY == other.originY &&
            widthM == other.widthM &&
            heightM == other.heightM
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (image?.contentHashCode() ?: 0)
        result = 31 * result + hasPlan.hashCode()
        result = 31 * result + originX.hashCode()
        result = 31 * result + originY.hashCode()
        result = 31 * result + widthM.hashCode()
        result = 31 * result + heightM.hashCode()
        return result
    }
}

/**
 * 벽에 설치된 측위 로케이터 한 대.
 *
 * 포팅 원본: SpaceModels.swift 의 `Locator`.
 */
public data class Locator @JvmOverloads constructor(
    /** UWB MAC 뒤 2바이트 (예: 0x9DD7) */
    public val address: Int,
    /** 도면 로컬 미터 */
    public val x: Double,
    public val y: Double,
    public val z: Double,
    /**
     * 서버가 이 로케이터를 배치 완료로 보고 있는가. 거짓이면 설치·설정이 끝나지 않은 것이라
     * 신호가 안 잡히는 게 정상이다. 모르는 것을 고장으로 치지 않도록 기본값은 true.
     */
    public val isPlaced: Boolean = true,
)

/**
 * 한 층의 로케이터 묶음. `sessionId`(UWB networkIdentifier)는 층마다 다르다.
 *
 * 포팅 원본: SpaceModels.swift 의 `FloorLocators`.
 */
public data class FloorLocators(
    public val locators: List<Locator>,
    public val sessionId: Int?,
) {
    /** 로케이터와 세션이 모두 있어 측위를 시도할 수 있는가. */
    public val positioningReady: Boolean get() = locators.isNotEmpty() && sessionId != null
}

/**
 * 측위·판정에 쓰는 층 상태 — 도면은 여기 없다(지도는 앱이 [Floor] 로 그린다).
 *
 * 포팅 원본: SpaceModels.swift 의 `FloorState`.
 */
internal data class FloorState(
    val buildingId: String,
    val floorId: String,
    val sessionId: Int?,
    val locators: List<Locator>,
    /** 존은 폴링으로 늦게 채워질 수 있어 var. */
    var zones: List<Zone>,
    /** 이 층에 도면이 등록돼 있는가. 도면이 없어도 측위는 정상이다. */
    val hasPlan: Boolean,
    /** 로케이터 조회가 실패했는가(통신·서버·404). 도면·존 표시는 막지 않는다. */
    val locatorsFetchFailed: Boolean,
)
