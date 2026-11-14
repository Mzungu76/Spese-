package it.spese.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/** Disegni del catalogo [it.spese.core.model.CategoryIcons]: tutte icone a linea (Outlined), per uno stile uniforme. */
object CategoryIconSet {
    private val ICONS: Map<String, ImageVector> = mapOf(
        "cart" to Icons.Outlined.ShoppingCart, "restaurant" to Icons.Outlined.Restaurant, "coffee" to Icons.Outlined.LocalCafe,
        "smoking" to Icons.Outlined.SmokingRooms, "car" to Icons.Outlined.DirectionsCar, "fuel" to Icons.Outlined.LocalGasStation,
        "transit" to Icons.Outlined.DirectionsBus, "flight" to Icons.Outlined.Flight, "home" to Icons.Outlined.Home,
        "garden" to Icons.Outlined.Yard, "tools" to Icons.Outlined.Build, "bolt" to Icons.Outlined.Bolt,
        "water" to Icons.Outlined.WaterDrop, "wifi" to Icons.Outlined.Wifi, "phone" to Icons.Outlined.PhoneAndroid,
        "receipt" to Icons.Outlined.ReceiptLong, "health" to Icons.Outlined.MedicalServices, "pharmacy" to Icons.Outlined.LocalPharmacy,
        "fitness" to Icons.Outlined.FitnessCenter, "clothes" to Icons.Outlined.Checkroom, "bag" to Icons.Outlined.ShoppingBag,
        "gift" to Icons.Outlined.CardGiftcard, "pets" to Icons.Outlined.Pets, "child" to Icons.Outlined.ChildCare,
        "family" to Icons.Outlined.FamilyRestroom, "school" to Icons.Outlined.School, "movie" to Icons.Outlined.Movie,
        "music" to Icons.Outlined.MusicNote, "subscriptions" to Icons.Outlined.Subscriptions, "card" to Icons.Outlined.CreditCard,
        "cash" to Icons.Outlined.Payments, "bank" to Icons.Outlined.AccountBalance, "shield" to Icons.Outlined.Shield,
        "work" to Icons.Outlined.Work, "savings" to Icons.Outlined.Savings, "trend" to Icons.Outlined.TrendingUp,
        "swap" to Icons.Outlined.SwapHoriz, "refund" to Icons.Outlined.CurrencyExchange, "category" to Icons.Outlined.Category,
        "more" to Icons.Outlined.MoreHoriz,
    )

    fun vector(key: String): ImageVector = ICONS[key] ?: Icons.Outlined.Category
}
