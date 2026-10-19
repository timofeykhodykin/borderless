package app.borderless.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Marks of a limited-network server: the cell tower, and the same tower without its signal arcs when it gave no reply. */
object TowerIcons {
    val tower: ImageVector get() = Icons.Rounded.CellTower

    /** Material's rounded cell tower (Apache 2.0) with only the mast left: "no signal". */
    val silent: ImageVector by lazy {
        ImageVector.Builder("TowerSilent", 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(MAST), fill = SolidColor(Color.Black))
            .build()
    }

    /** The mast and its antenna of `cell_tower` (materialiconsround/24px.svg); the four arcs are left out. */
    private const val MAST = "M14.5,10c0-1.6-1.51-2.85-3.18-2.41c-0.8,0.21-1.46,0.85-1.7,1.65c-0.32,1.06,0.06,2.04,0.76,2.64" +
        "l-2.96,8.87C7.21,21.37,7.67,22,8.32,22h0c0.41,0,0.77-0.26,0.9-0.65L9.67,20h4.67l0.45,1.35c0.13,0.39,0.49,0.65,0.9,0.65" +
        "h0c0.65,0,1.1-0.63,0.9-1.25l-2.96-8.87C14.16,11.42,14.5,10.76,14.5,10z M10.33,18L12,13l1.67,5H10.33z"
}
