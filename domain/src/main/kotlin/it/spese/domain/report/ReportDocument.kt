package it.spese.domain.report

/** Contenuto di un report, indipendente dall'impaginazione e dal formato di stampa. */
enum class Tone { NORMAL, POSITIVE, NEGATIVE, MUTED, WARNING }
enum class Align { LEFT, RIGHT, CENTER }

data class Kpi(val label: String, val value: String, val sub: String? = null, val tone: Tone = Tone.NORMAL)
data class Col(val header: String, val align: Align = Align.LEFT, val weight: Float = 1f)
data class TRow(
    val cells: List<String>, val bold: Boolean = false, val indent: Boolean = false, val tones: Map<Int, Tone> = emptyMap(),
    /** Riga di intestazione di gruppo: sfondo e testo in evidenza, occupa tutta la larghezza. */
    val group: Boolean = false,
)
data class BarItem(val label: String, val value: Double, val text: String, val color: Int)
data class Series(val name: String, val color: Int, val values: List<Double>)

sealed interface Block {
    data class Heading(val text: String) : Block
    data class Paragraph(val text: String, val tone: Tone = Tone.NORMAL) : Block
    data class Kpis(val items: List<Kpi>) : Block
    data class Table(val title: String?, val columns: List<Col>, val rows: List<TRow>, val footer: TRow? = null) : Block
    /** Barre orizzontali (una per voce). */
    data class Bars(val title: String?, val items: List<BarItem>) : Block
    /** Barre verticali raggruppate per punto dell'asse, con legenda. */
    data class Columns(val title: String?, val labels: List<String>, val series: List<Series>) : Block
    data object PageBreak : Block
}

data class ReportDocument(val title: String, val subtitle: String, val generatedAt: String, val note: String, val blocks: List<Block>, val landscape: Boolean, val empty: Boolean = false)
