package com.shilapi.xcertplay

import com.shilapi.xcertplay.CarPlayViewAreas.Kind
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayViewAreasTest {
    // On a Tang, BYD's split screen gives DiPlay 1270x1208 of the 2560x1440 screen.
    private val split = 1270f / 2560 to 1208f / 1440

    @Test
    fun automaticDockWithoutSplitScreenNeedsNoAreas() {
        assertNull(CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, splitWindow = null))
    }

    @Test
    fun fixedDockDeclaresTheScreenOncePerEdgeAndStartsOnTheChosenOne() {
        val driver = CarPlayViewAreas.build(2560, 1440, CarPlayDock.DRIVER_SIDE, splitWindow = null)!!
        assertEquals(listOf(DOCK_EDGE_DRIVER_SIDE, DOCK_EDGE_BOTTOM), driver.areas.map { it.dockEdge })
        assertEquals(0, driver.current)
        val bottom = CarPlayViewAreas.build(2560, 1440, CarPlayDock.BOTTOM, splitWindow = null)!!
        assertEquals(1, bottom.current)
        assertEquals(0, bottom.withDock(DOCK_EDGE_DRIVER_SIDE))
    }

    @Test
    fun splitScreenAddsTheWindowSizedAreaPerEdge() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.BOTTOM, split)!!
        assertEquals(4, areas.areas.size)
        assertEquals(listOf(Kind.FULL_SCREEN, Kind.FULL_SCREEN, Kind.SPLIT_SCREEN, Kind.SPLIT_SCREEN), areas.areas.indices.map(areas::kindOf))
        val splitBottom = areas.index(Kind.SPLIT_SCREEN, DOCK_EDGE_BOTTOM)!!
        assertEquals(1270, areas.areas[splitBottom].width)
        assertEquals(1208, areas.areas[splitBottom].height)
    }

    @Test
    fun theWindowPicksSplitScreenOnlyWhenSplitAndCloseInShape() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, split)!!
        assertEquals(0, areas.current)
        assertEquals(1, areas.indexFor(1270, 1208, splitScreen = true))
        // A full-size window, or a window far from the area's shape (a floating one), keeps the whole screen.
        assertEquals(0, areas.indexFor(2560, 1440, splitScreen = false))
        assertEquals(0, areas.indexFor(600, 1200, splitScreen = true))
    }

    @Test
    fun theDockMovesWithinTheSameKindOfArea() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.DRIVER_SIDE, split)!!
        areas.use(areas.indexFor(1270, 1208, splitScreen = true)!!)
        assertEquals(Kind.SPLIT_SCREEN, areas.kindOf(areas.current))
        val moved = areas.withDock(DOCK_EDGE_BOTTOM)!!
        assertEquals(Kind.SPLIT_SCREEN, areas.kindOf(moved))
        assertEquals(DOCK_EDGE_BOTTOM, areas.areas[moved].dockEdge)
    }

    private fun turning(dock: CarPlayDock = CarPlayDock.AUTOMATIC, splits: Boolean = false, startPortrait: Boolean = false) =
        CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, portrait = false),
            CarPlayViewAreas.Screen(1440, 2560, portrait = true),
        ), dock, splitWindow = { portrait ->
            if (!splits) null else if (portrait) 1f to 1154f / 2560 else split
        }, startPortrait = startPortrait)!!

    @Test
    fun aSquareCanvasHoldsBothScreensAndStartsInTheWindowsOne() {
        val areas = turning()
        assertEquals(true, areas.turnsWithScreen)
        assertEquals(listOf(2560 to 1440, 1440 to 2560), areas.areas.map { it.width to it.height })
        assertEquals(0, areas.current)
        assertEquals(1, turning(startPortrait = true).current)
    }

    @Test
    fun turningPicksTheOtherScreenAndSplitScreenPicksItsOrientationsArea() {
        val areas = turning(splits = true)
        assertEquals(1, areas.indexFor(1440, 2560, splitScreen = false, portrait = true))
        val portraitSplit = areas.indexFor(1440, 1154, splitScreen = true, portrait = true)!!
        assertEquals(Kind.SPLIT_SCREEN, areas.kindOf(portraitSplit))
        assertEquals(1440 to 1154, areas.areas[portraitSplit].let { it.width to it.height })
        val landscapeSplit = areas.indexFor(1270, 1208, splitScreen = true, portrait = false)!!
        assertEquals(1270 to 1208, areas.areas[landscapeSplit].let { it.width to it.height })
    }

    @Test
    fun aPlainCanvasHasNoAreaForTheOtherScreen() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.BOTTOM, splitWindow = null)!!
        assertEquals(false, areas.turnsWithScreen)
        assertNull(areas.indexFor(1440, 2560, splitScreen = false, portrait = true))
    }

    @Test
    fun aSquareCanvasNeedsAreasEvenWithAnAutomaticDock() {
        val areas = turning()
        assertEquals(listOf<Int?>(null, null), areas.areas.map { it.dockEdge })
    }

    @Test
    fun theSidePanelSitsOnTheRightOrAtTheBottomOfCarPlaysTwoThirds() {
        val areas = CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, portrait = false),
            CarPlayViewAreas.Screen(1440, 2560, portrait = true),
        ), CarPlayDock.AUTOMATIC, splitWindow = { null }, startPortrait = false, sidePanel = true)!!
        val landscape = areas.sidePanel(portrait = false)!!
        assertEquals(Kind.SIDE_PANEL, areas.kindOf(landscape))
        assertEquals(1706 to 1440, areas.areas[landscape].let { it.width to it.height })
        assertEquals(0, areas.areas[landscape].originX)
        val portrait = areas.sidePanel(portrait = true)!!
        assertEquals(1440 to 1706, areas.areas[portrait].let { it.width to it.height })
        // The canvas is laid out by the whole screen; the panel covers the rest.
        assertEquals(2560 to 1440, areas.layoutArea(landscape).let { it.width to it.height })
        assertEquals(1440 to 2560, areas.layoutArea(portrait).let { it.width to it.height })
        assertEquals(AirPlayViewArea(854, 1440, 1706, 0), areas.panelRect(landscape))
        assertEquals(AirPlayViewArea(1440, 854, 0, 1706), areas.panelRect(portrait))
        assertNull(areas.panelRect(areas.current))
    }

    @Test
    fun aRightHandDriveCarKeepsCarPlayOnTheRightWithThePanelOnTheLeft() {
        val areas = CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, portrait = false),
            CarPlayViewAreas.Screen(1440, 2560, portrait = true),
        ), CarPlayDock.AUTOMATIC, splitWindow = { null }, startPortrait = false, sidePanel = true, rightHandDrive = true)!!
        val landscape = areas.sidePanel(portrait = false)!!
        assertEquals(854, areas.areas[landscape].originX)
        assertEquals(AirPlayViewArea(854, 1440, 0, 0), areas.panelRect(landscape))
        // A portrait screen keeps the panel at the bottom.
        assertEquals(AirPlayViewArea(1440, 854, 0, 1706), areas.panelRect(areas.sidePanel(portrait = true)!!))
    }

    @Test
    fun aWindowNeverPicksTheSidePanelByItself() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, splitWindow = null, sidePanel = true)!!
        assertEquals(0, areas.indexFor(2560, 1440, splitScreen = false))
        assertEquals(0, areas.indexFor(1706, 1440, splitScreen = false))
        areas.use(areas.sidePanel()!!)
        assertEquals(0, areas.indexFor(2560, 1440, splitScreen = false))
    }

    @Test
    fun theDockMovesBesideTheSidePanelToo() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.DRIVER_SIDE, splitWindow = null, sidePanel = true)!!
        areas.use(areas.sidePanel()!!)
        val moved = areas.withDock(DOCK_EDGE_BOTTOM)!!
        assertEquals(Kind.SIDE_PANEL, areas.kindOf(moved))
        assertEquals(DOCK_EDGE_BOTTOM, areas.areas[moved].dockEdge)
    }
}
