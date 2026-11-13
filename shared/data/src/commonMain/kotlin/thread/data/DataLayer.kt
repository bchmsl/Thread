package thread.data

import thread.domain.DomainLayer

/**
 * Phase 0 build marker. [DEPENDS_ON] compiles only because data depends on domain.
 * Delete it when real data code lands (Phase 1).
 */
object DataLayer {
    const val NAME: String = "data"
    const val DEPENDS_ON: String = DomainLayer.NAME
}
