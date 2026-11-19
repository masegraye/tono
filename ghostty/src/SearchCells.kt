package org.example.kotlintest.ghostty

/**
 * The cells of the view that a run of text covers, given where its two ends are in the
 * view. An end is null when it is out of view: the first above the top, the last below
 * the bottom. Text that runs over the end of a row carries on from the start of the next.
 * Empty when neither end is in view.
 */
internal fun cellsBetween(
    firstColumn: Int?, firstRow: Int?,
    lastColumn: Int?, lastRow: Int?,
    columns: Int, rows: Int,
): List<SelectedCells> {
    if (columns <= 0 || rows <= 0) return emptyList()
    val startsInView = firstColumn != null && firstRow != null && firstRow < rows
    val endsInView = lastColumn != null && lastRow != null && lastRow < rows
    // Neither end in view: almost always a match somewhere else. One that covers the whole
    // view from above it to below it would take text longer than a screen.
    if (!startsInView && !endsInView) return emptyList()
    val fromRow = if (startsInView) firstRow else 0
    val fromColumn = if (startsInView) firstColumn else 0
    val toRow = if (endsInView) lastRow else rows - 1
    val toColumn = if (endsInView) lastColumn else columns - 1
    if (toRow < fromRow || (toRow == fromRow && toColumn < fromColumn)) return emptyList()
    return (fromRow..toRow).map { row ->
        SelectedCells(
            row = row,
            firstColumn = if (row == fromRow) fromColumn.coerceIn(0, columns - 1) else 0,
            lastColumn = if (row == toRow) toColumn.coerceIn(0, columns - 1) else columns - 1,
        )
    }
}
