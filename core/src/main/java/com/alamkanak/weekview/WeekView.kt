package com.alamkanak.weekview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import java.util.Calendar
import kotlin.math.min
import kotlin.math.roundToInt

class WeekView<T : Any> @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var viewState: WeekViewViewState = WeekViewViewState(context, attrs)

    private val cache = WeekViewCache<T>()
    private val eventChipCache = EventChipCache<T>()

    private val touchHandler = WeekViewTouchHandler(viewState, eventChipCache)

    private val gestureHandler = WeekViewGestureHandler(
        context,
        viewState,
        eventChipCache,
        touchHandler,
        onInvalidation = { ViewCompat.postInvalidateOnAnimation(this) }
    )

    private var accessibilityTouchHelper: WeekViewAccessibilityTouchHelper<T>? = null

    private val eventChipsLoader = EventChipsLoader(viewState, eventChipCache)
    private val eventChipsExpander = EventChipsExpander(viewState, eventChipCache)

    internal val eventsCacheWrapper = EventsCacheWrapper<T>()
    internal val eventsLoaderWrapper = EventsLoaderWrapper(eventsCacheWrapper)

    private val eventsDiffer = EventsDiffer(eventsCacheWrapper, eventChipsLoader, viewState)

    private val eventsLoader: EventsLoader<T>
        get() = eventsLoaderWrapper.get()

    private var _dateTimeInterpreter: DateTimeInterpreter =
        DefaultDateTimeInterpreter(RealDateFormatProvider(context), numberOfVisibleDays)

    var dateTimeInterpreter: DateTimeInterpreter
        get() = _dateTimeInterpreter
        set(value) {
            _dateTimeInterpreter = value
            clearCaches()
        }

    // Be careful when changing the order of the updaters, as the calculation of any updater might
    // depend on results of previous updaters
    private val updaters = listOf(
        MultiLineDayLabelHeightUpdater(cache, dateTimeInterpreter),
        AllDayEventsUpdater(context, cache, eventChipCache),
        HeaderRowHeightUpdater(eventsCacheWrapper),
        TimeColumnUpdater(dateTimeInterpreter),
        SingleEventsUpdater(eventChipCache)
    )

    init {
        // viewState.dateTimeInterpreter = dateTimeInterpreter

        if (context.isAccessibilityEnabled) {
            accessibilityTouchHelper = WeekViewAccessibilityTouchHelper(
                this,
                viewState,
                gestureHandler,
                eventChipCache,
                touchHandler
            )
            ViewCompat.setAccessibilityDelegate(this, accessibilityTouchHelper)
        }
    }

    // Be careful when changing the order of the drawers, as that might cause
    // views to incorrectly draw over each other
    private val drawers = listOf(
        DayBackgroundDrawer,
        BackgroundGridDrawer,
        SingleEventsDrawer(context, viewState, eventChipCache),
        NowLineDrawer,
        TimeColumnDrawer(viewState, dateTimeInterpreter),
        HeaderRowDrawer,
        DayLabelDrawer(cache, dateTimeInterpreter),
        AllDayEventsDrawer(context, viewState, cache)
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateViewState()

        viewState.goToDate?.let { date ->
            goToDate(date)
        }

        viewState.goToHour?.let { hour ->
            goToHour(hour)
        }

        notifyScrollListeners()
        refreshEvents()
        updateDimensions()
        performDrawing(canvas)
    }

    private fun updateViewState() {
        viewState.update()
    }

    private fun refreshEvents() {
        if (isInEditMode) {
            return
        }

        val firstVisibleDate = checkNotNull(viewState.firstVisibleDate)

        // These can either be newly loaded events or previously cached events
        val events = eventsLoader.refresh(firstVisibleDate)
        eventChipCache.clear()

        if (events.isNotEmpty()) {
            eventChipsLoader.createAndCacheEventChips(events)
            eventChipsExpander.calculateEventChipPositions()
        }
    }

    private fun updateDimensions() {
        for (updater in updaters) {
            if (updater.isRequired(viewState)) {
                updater.update(viewState)
            }
        }
    }

    private fun performDrawing(canvas: Canvas) {
        for (drawer in drawers) {
            drawer.draw(viewState, canvas)
        }

        accessibilityTouchHelper?.invalidateRoot()
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        return superState?.let {
            SavedState(it, viewState)
        }
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        val savedState = state as SavedState
        super.onRestoreInstanceState(savedState.superState)

        viewState = savedState.viewState
        invalidate()

//        if (viewState.restoreNumberOfVisibleDays) {
//            viewState.numberOfVisibleDays = savedState.viewState.numberOfVisibleDays
//        }

//        viewState.firstVisibleDate?.let {
//            goToDate(it)
//        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        viewState.onSizeChanged(width, height, dateTimeInterpreter)

        // todo move this, have the clearing be initiated by viewstate somehow
        clearCaches()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        viewState.x = x
        viewState.y = y
    }

    private fun notifyScrollListeners() {
        val oldFirstVisibleDay = viewState.firstVisibleDate
        val totalDayWidth = viewState.totalDayWidth
        val visibleDays = viewState.numberOfVisibleDays

        val daysScrolled = viewState.currentOrigin.x / totalDayWidth
        val delta = daysScrolled.roundToInt() * (-1)

        val firstVisibleDate = today() + Days(delta)
        val lastVisibleDate = firstVisibleDate + Days(visibleDays - 1)

        viewState.firstVisibleDate = firstVisibleDate
        viewState.lastVisibleDate = lastVisibleDate

        val hasFirstVisibleDayChanged = oldFirstVisibleDay?.let {
            firstVisibleDate.isSameDate(it).not()
        } ?: true

        if (hasFirstVisibleDayChanged) {
            scrollListener?.onFirstVisibleDateChanged(firstVisibleDate)
            onRangeChangeListener?.onRangeChanged(firstVisibleDate, lastVisibleDate)
        }
    }

    private fun calculateWidthPerDay() {
        viewState.calculateWidthPerDay(dateTimeInterpreter)
    }

    override fun invalidate() {
        viewState.invalidate()
        super.invalidate()
    }

    /*
     ***********************************************************************************************
     *
     *   Calendar configuration
     *
     ***********************************************************************************************
     */

    /**
     * Returns the first day of the week. Possible values are [java.util.Calendar.SUNDAY],
     * [java.util.Calendar.MONDAY], [java.util.Calendar.TUESDAY],
     * [java.util.Calendar.WEDNESDAY], [java.util.Calendar.THURSDAY],
     * [java.util.Calendar.FRIDAY], [java.util.Calendar.SATURDAY].
     */
    var firstDayOfWeek: Int
        get() = viewState.firstDayOfWeek
        set(value) {
            viewState.firstDayOfWeek = value
            invalidate()
        }

    /**
     * Returns the number of visible days.
     */
    var numberOfVisibleDays: Int
        get() = viewState.numberOfVisibleDays
        set(value) {
            viewState.numberOfVisibleDays = value
            dateTimeInterpreter.onSetNumberOfDays(value)
            clearCaches()

            // TODO Unify this stuff

            viewState.firstVisibleDate?.let {
                // Scroll to first visible day after changing the number of visible days
                viewState.goToDate = it
            }

            calculateWidthPerDay()
            invalidate()
        }

    /**
     * Returns whether the first day of the week should be displayed at the left-most position
     * when WeekView is displayed for the first time.
     */
    var isShowFirstDayOfWeekFirst: Boolean
        get() = viewState.showFirstDayOfWeekFirst
        set(value) {
            viewState.showFirstDayOfWeekFirst = value
        }

    /*
     ***********************************************************************************************
     *
     *   Header bottom line
     *
     ***********************************************************************************************
     */

    var isShowHeaderRowBottomLine: Boolean
        /**
         * Returns whether a horizontal line should be displayed at the bottom of the header row.
         */
        get() = viewState.showHeaderRowBottomLine
        /**
         * Sets whether a horizontal line should be displayed at the bottom of the header row.
         */
        set(value) {
            viewState.showHeaderRowBottomLine = value
            invalidate()
        }

    var headerRowBottomLineColor: Int
        /**
         * Returns the color of the horizontal line at the bottom of the header row.
         */
        get() = viewState.headerRowBottomLinePaint.color
        /**
         * Sets the color of the horizontal line at the bottom of the header row. Whether the line
         * is displayed, is determined by [isShowHeaderRowBottomLine]
         */
        set(value) {
            viewState.headerRowBottomLinePaint.color = value
            invalidate()
        }

    var headerRowBottomLineWidth: Int
        /**
         * Returns the stroke width of the horizontal line at the bottom of the header row.
         */
        get() = viewState.headerRowBottomLinePaint.strokeWidth.toInt()
        /**
         * Sets the stroke width of the horizontal line at the bottom of the header row. Whether the
         * line is displayed, is determined by [isShowHeaderRowBottomLine]
         */
        set(value) {
            viewState.headerRowBottomLinePaint.strokeWidth = value.toFloat()
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Time column
     *
     ***********************************************************************************************
     */

    /**
     * Returns the padding in the time column to the left and right side of the time label.
     */
    var timeColumnPadding: Int
        get() = viewState.timeColumnPadding
        set(value) {
            viewState.timeColumnPadding = value
            invalidate()
        }

    /**
     * Returns the text color of the labels in the time column.
     */
    var timeColumnTextColor: Int
        get() = viewState.timeColumnTextColor
        set(value) {
            viewState.timeColumnTextColor = value
            invalidate()
        }

    /**
     * Returns the background color of the time column.
     */
    var timeColumnBackgroundColor: Int
        get() = viewState.timeColumnBackgroundColor
        set(value) {
            viewState.timeColumnBackgroundColor = value
            invalidate()
        }

    /**
     * Returns the text size of the labels in the time column.
     */
    var timeColumnTextSize: Int
        get() = viewState.timeColumnTextSize
        set(value) {
            viewState.timeColumnTextSize = value
            invalidate()
        }

    /**
     * Returns whether the label for the midnight hour is displayed in the time column. This setting
     * is only considered if [isShowTimeColumnHourSeparator] is set to true.
     */
    var isShowMidnightHour: Boolean
        get() = viewState.showMidnightHour
        set(value) {
            viewState.showMidnightHour = value
            invalidate()
        }

    /**
     * Returns whether a horizontal line is displayed for each hour in the time column.
     */
    var isShowTimeColumnHourSeparator: Boolean
        get() = viewState.showTimeColumnHourSeparator
        set(value) {
            viewState.showTimeColumnHourSeparator = value
            invalidate()
        }

    /**
     * Returns the interval in which time labels are displayed in the time column.
     */
    var timeColumnHoursInterval: Int
        get() = viewState.timeColumnHoursInterval
        set(value) {
            viewState.timeColumnHoursInterval = value
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Time column separator
     *
     ***********************************************************************************************
     */

    /**
     * Returns whether a vertical line is displayed at the end of the time column.
     */
    var isShowTimeColumnSeparator: Boolean
        get() = viewState.showTimeColumnSeparator
        set(value) {
            viewState.showTimeColumnSeparator = value
            invalidate()
        }

    /**
     * Returns the color of the time column separator.
     */
    var timeColumnSeparatorColor: Int
        get() = viewState.timeColumnSeparatorColor
        set(value) {
            viewState.timeColumnSeparatorColor = value
            invalidate()
        }

    /**
     * Returns the stroke width of the time column separator.
     */
    var timeColumnSeparatorWidth: Int
        get() = viewState.timeColumnSeparatorStrokeWidth
        set(value) {
            viewState.timeColumnSeparatorStrokeWidth = value
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Header row
     *
     ***********************************************************************************************
     */

    /**
     * Returns the header row padding, which is applied above and below the all-day event chips.
     */
    var headerRowPadding: Int
        get() = viewState.headerRowPadding
        set(value) {
            viewState.headerRowPadding = value
            invalidate()
        }

    /**
     * Returns the header row background color.
     */
    var headerRowBackgroundColor: Int
        get() = viewState.headerRowBackgroundColor
        set(value) {
            viewState.headerRowBackgroundColor = value
            invalidate()
        }

    /**
     * Returns the text color used for all date labels except today.
     */
    var headerRowTextColor: Int
        get() = viewState.headerRowTextColor
        set(value) {
            viewState.headerRowTextColor = value
            invalidate()
        }

    /**
     * Returns the text color used for today's date label.
     */
    var todayHeaderTextColor: Int
        get() = viewState.todayHeaderTextColor
        set(value) {
            viewState.todayHeaderTextColor = value
            invalidate()
        }

    /**
     * Returns the text size of all date labels.
     */
    var headerRowTextSize: Int
        get() = viewState.headerRowTextSize
        set(value) {
            viewState.headerRowTextSize = value
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Event chips
     *
     ***********************************************************************************************
     */

    /**
     * Returns the corner radius of an [EventChip].
     */
    var eventCornerRadius: Int
        get() = viewState.eventCornerRadius
        set(value) {
            viewState.eventCornerRadius = value
            invalidate()
        }

    /**
     * Returns the text size of a single-event [EventChip].
     */
    var eventTextSize: Int
        get() = viewState.eventTextPaint.textSize.toInt()
        set(value) {
            viewState.eventTextPaint.textSize = value.toFloat()
            invalidate()
        }

    /**
     * Returns whether the text size of the [EventChip] is adapting to the [EventChip] height.
     */
    var isAdaptiveEventTextSize: Boolean
        get() = viewState.adaptiveEventTextSize
        set(value) {
            viewState.adaptiveEventTextSize = value
            invalidate()
        }

    /**
     * Returns the text size of an all-day [EventChip].
     */
    var allDayEventTextSize: Int
        get() = viewState.allDayEventTextPaint.textSize.toInt()
        set(value) {
            viewState.allDayEventTextPaint.textSize = value.toFloat()
            invalidate()
        }

    /**
     * Returns the default text color of an [EventChip].
     */
    var defaultEventTextColor: Int
        get() = viewState.eventTextPaint.color
        set(value) {
            viewState.eventTextPaint.color = value
            invalidate()
        }

    /**
     * Returns the horizontal padding within an [EventChip].
     */
    var eventPaddingHorizontal: Int
        get() = viewState.eventPaddingHorizontal
        set(value) {
            viewState.eventPaddingHorizontal = value
            invalidate()
        }

    /**
     * Returns the vertical padding within an [EventChip].
     */
    var eventPaddingVertical: Int
        get() = viewState.eventPaddingVertical
        set(value) {
            viewState.eventPaddingVertical = value
            invalidate()
        }

    /**
     * Returns the default text color of an [EventChip].
     */
    var defaultEventColor: Int
        get() = viewState.defaultEventColor
        set(value) {
            viewState.defaultEventColor = value
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Event margins
     *
     ***********************************************************************************************
     */

    /**
     * Returns the column gap at the end of each day.
     */
    var columnGap: Int
        get() = viewState.columnGap
        set(value) {
            viewState.columnGap = value
            invalidate()
        }

    /**
     * Returns the horizontal gap between overlapping [EventChip]s.
     */
    var overlappingEventGap: Int
        get() = viewState.overlappingEventGap
        set(value) {
            viewState.overlappingEventGap = value
            invalidate()
        }

    /**
     * Returns the vertical margin of an [EventChip].
     */
    var eventMarginVertical: Int
        get() = viewState.eventMarginVertical
        set(value) {
            viewState.eventMarginVertical = value
            invalidate()
        }

    /**
     * Returns the horizontal margin of an [EventChip]. This margin is only applied in single-day
     * view and if there are no overlapping events.
     */
    var eventMarginHorizontal: Int
        get() = viewState.eventMarginHorizontal
        set(value) {
            viewState.eventMarginHorizontal = value
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Colors
     *
     ***********************************************************************************************
     */

    /**
     * Returns the background color of a day.
     */
    var dayBackgroundColor: Int
        get() = viewState.dayBackgroundPaint.color
        set(value) {
            viewState.dayBackgroundPaint.color = value
            invalidate()
        }

    /**
     * Returns the background color of the current date.
     */
    var todayBackgroundColor: Int
        get() = viewState.todayBackgroundPaint.color
        set(value) {
            viewState.todayBackgroundPaint.color = value
            invalidate()
        }

    /**
     * Returns whether weekends should have a background color different from [dayBackgroundColor].
     *
     * The weekend background colors can be defined by [pastWeekendBackgroundColor] and
     * [futureWeekendBackgroundColor].
     */
    var isShowDistinctWeekendColor: Boolean
        get() = viewState.showDistinctWeekendColor
        set(value) {
            viewState.showDistinctWeekendColor = value
            invalidate()
        }

    /**
     * Returns whether past and future days should have background colors different from
     * [dayBackgroundColor].
     *
     * The past and future day colors can be defined by [pastBackgroundColor] and
     * [futureBackgroundColor].
     */
    var isShowDistinctPastFutureColor: Boolean
        get() = viewState.showDistinctPastFutureColor
        set(value) {
            viewState.showDistinctPastFutureColor = value
            invalidate()
        }

    /**
     * Returns the background color for past dates. If not explicitly set, WeekView will used
     * [dayBackgroundColor].
     */
    var pastBackgroundColor: Int
        get() = viewState.pastBackgroundPaint.color
        set(value) {
            viewState.pastBackgroundPaint.color = value
            invalidate()
        }

    /**
     * Returns the background color for past weekend dates. If not explicitly set, WeekView will
     * used [pastBackgroundColor].
     */
    var pastWeekendBackgroundColor: Int
        get() = viewState.pastWeekendBackgroundPaint.color
        set(value) {
            viewState.pastWeekendBackgroundPaint.color = value
            invalidate()
        }

    /**
     * Returns the background color for future dates. If not explicitly set, WeekView will used
     * [dayBackgroundColor].
     */
    var futureBackgroundColor: Int
        get() = viewState.futureBackgroundPaint.color
        set(value) {
            viewState.futureBackgroundPaint.color = value
            invalidate()
        }

    /**
     * Returns the background color for future weekend dates. If not explicitly set, WeekView will
     * used [futureBackgroundColor].
     */
    var futureWeekendBackgroundColor: Int
        get() = viewState.futureWeekendBackgroundPaint.color
        set(value) {
            viewState.futureWeekendBackgroundPaint.color = value
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Hour height
     *
     ***********************************************************************************************
     */

    /**
     * Returns the current height of an hour.
     */
    var hourHeight: Float
        get() = viewState.hourHeight
        set(value) {
            viewState.newHourHeight = value
            invalidate()
        }

    /**
     * Returns the minimum height of an hour.
     */
    var minHourHeight: Int
        get() = viewState.minHourHeight
        set(value) {
            viewState.minHourHeight = value
            invalidate()
        }

    /**
     * Returns the maximum height of an hour.
     */
    var maxHourHeight: Int
        get() = viewState.maxHourHeight
        set(value) {
            viewState.maxHourHeight = value
            invalidate()
        }

    /**
     * Returns whether the complete day should be shown, in which case [hourHeight] automatically
     * adjusts to accommodate all hours between [minHour] and [maxHour].
     */
    var isShowCompleteDay: Boolean
        get() = viewState.showCompleteDay
        set(value) {
            viewState.showCompleteDay = value
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Now line
     *
     ***********************************************************************************************
     */

    /**
     * Returns whether a horizontal line should be displayed at the current time.
     */
    var isShowNowLine: Boolean
        get() = viewState.showNowLine
        set(value) {
            viewState.showNowLine = value
            invalidate()
        }

    /**
     * Returns the color of the horizontal "now" line.
     */
    var nowLineColor: Int
        get() = viewState.nowLinePaint.color
        set(value) {
            viewState.nowLinePaint.color = value
            invalidate()
        }

    /**
     * Returns the stroke width of the horizontal "now" line.
     */
    var nowLineStrokeWidth: Int
        get() = viewState.nowLinePaint.strokeWidth.toInt()
        set(value) {
            viewState.nowLinePaint.strokeWidth = value.toFloat()
            invalidate()
        }

    /**
     * Returns whether a dot at the start of the "now" line is displayed. The dot is only displayed
     * if [isShowNowLine] is set to true.
     */
    var isShowNowLineDot: Boolean
        get() = viewState.showNowLineDot
        set(value) {
            viewState.showNowLineDot = value
            invalidate()
        }

    /**
     * Returns the color of the dot at the start of the "now" line.
     */
    var nowLineDotColor: Int
        get() = viewState.nowDotPaint.color
        set(value) {
            viewState.nowDotPaint.color = value
            invalidate()
        }

    /**
     * Returns the radius of the dot at the start of the "now" line.
     */
    var nowLineDotRadius: Int
        get() = viewState.nowDotPaint.strokeWidth.toInt()
        set(value) {
            viewState.nowDotPaint.strokeWidth = value.toFloat()
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Hour separators
     *
     ***********************************************************************************************
     */

    var isShowHourSeparators: Boolean
        get() = viewState.showHourSeparators
        set(value) {
            viewState.showHourSeparators = value
            invalidate()
        }

    var hourSeparatorColor: Int
        get() = viewState.hourSeparatorPaint.color
        set(value) {
            viewState.hourSeparatorPaint.color = value
            invalidate()
        }

    var hourSeparatorStrokeWidth: Int
        get() = viewState.hourSeparatorPaint.strokeWidth.toInt()
        set(value) {
            viewState.hourSeparatorPaint.strokeWidth = value.toFloat()
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Day separators
     *
     ***********************************************************************************************
     */

    /**
     * Returns whether vertical lines are displayed as separators between dates.
     */
    var isShowDaySeparators: Boolean
        get() = viewState.showDaySeparators
        set(value) {
            viewState.showDaySeparators = value
            invalidate()
        }

    /**
     * Returns the color of the separators between dates.
     */
    var daySeparatorColor: Int
        get() = viewState.daySeparatorPaint.color
        set(value) {
            viewState.daySeparatorPaint.color = value
            invalidate()
        }

    /**
     * Returns the stroke color of the separators between dates.
     */
    var daySeparatorStrokeWidth: Int
        get() = viewState.daySeparatorPaint.strokeWidth.toInt()
        set(value) {
            viewState.daySeparatorPaint.strokeWidth = value.toFloat()
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Date range
     *
     ***********************************************************************************************
     */

    /**
     * Returns the minimum date that [WeekView] will display, or null if none is set. Events before
     * this date will not be shown.
     */
    var minDate: Calendar?
        get() = viewState.minDate?.copy()
        set(value) {
            val maxDate = viewState.maxDate
            if (maxDate != null && value != null && value.isAfter(maxDate)) {
                throw IllegalArgumentException("Can't set a minDate that's after maxDate")
            }

            viewState.minDate = value
            invalidate()
        }

    /**
     * Returns the maximum date that [WeekView] will display, or null if none is set. Events after
     * this date will not be shown.
     */
    var maxDate: Calendar?
        get() = viewState.maxDate?.copy()
        set(value) {
            val minDate = viewState.minDate
            if (minDate != null && value != null && value.isBefore(minDate)) {
                throw IllegalArgumentException("Can't set a maxDate that's before minDate")
            }

            viewState.maxDate = value
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Time range
     *
     ***********************************************************************************************
     */

    /**
     * Returns the minimum hour that [WeekView] will display. Events before this time will not be
     * shown.
     */
    var minHour: Int
        get() = viewState.minHour
        set(value) {
            if (value < 0 || value > viewState.maxHour) {
                throw IllegalArgumentException("minHour must be between 0 and maxHour.")
            }

            viewState.minHour = value
            invalidate()
        }

    /**
     * Returns the maximum hour that [WeekView] will display. Events before this time will not be
     * shown.
     */
    var maxHour: Int
        get() = viewState.maxHour
        set(value) {
            if (value > 24 || value < viewState.minHour) {
                throw IllegalArgumentException("maxHour must be between minHour and 24.")
            }

            viewState.maxHour = value
            invalidate()
        }

    /*
     ***********************************************************************************************
     *
     *   Scrolling
     *
     ***********************************************************************************************
     */

    /**
     * Returns the scrolling speed factor in horizontal direction.
     */
    var xScrollingSpeed: Float
        get() = viewState.xScrollingSpeed
        set(value) {
            viewState.xScrollingSpeed = value
        }

    /**
     * Returns whether WeekView can fling horizontally.
     */
    var isHorizontalFlingEnabled: Boolean
        get() = viewState.horizontalFlingEnabled
        set(value) {
            viewState.horizontalFlingEnabled = value
        }

    /**
     * Returns whether WeekView can scroll horizontally.
     */
    var isHorizontalScrollingEnabled: Boolean
        get() = viewState.horizontalScrollingEnabled
        set(value) {
            viewState.horizontalScrollingEnabled = value
        }

    /**
     * Returns whether WeekView can fling vertically.
     */
    var isVerticalFlingEnabled: Boolean
        get() = viewState.verticalFlingEnabled
        set(value) {
            viewState.verticalFlingEnabled = value
        }

    var scrollDuration: Int
        get() = viewState.scrollDuration
        set(value) {
            viewState.scrollDuration = value
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestureHandler.onTouchEvent(event)

    override fun computeScroll() {
        super.computeScroll()
        gestureHandler.computeScroll()
    }

    /*
     ***********************************************************************************************
     *
     *   Date methods
     *
     ***********************************************************************************************
     */

    /**
     * Returns the first visible date.
     */
    val firstVisibleDate: Calendar?
        get() = viewState.firstVisibleDate?.copy()

    /**
     * Returns the last visible date.
     */
    val lastVisibleDate: Calendar?
        get() = viewState.lastVisibleDate?.copy()

    /**
     * Shows the current date.
     */
    fun goToToday() {
        goToDate(today())
    }

    /**
     * Shows the current date and time.
     */
    fun goToCurrentTime() {
        now().apply {
            goToDate(this)
            goToHour(hour)
        }
    }

    /**
     * Shows a specific date. If it is before [minDate] or after [maxDate], these will be shown
     * instead.
     *
     * @param date The date to show.
     */
    fun goToDate(date: Calendar) {
        val adjustedDate = viewState.getDateWithinDateRange(date)
        gestureHandler.forceScrollFinished()

        val isWaitingToBeLaidOut = ViewCompat.isLaidOut(this).not()
        if (isWaitingToBeLaidOut) {
            // If the view's dimensions have just changed or if it hasn't been laid out yet, we
            // postpone the action until onDraw() is called the next time.
            viewState.goToDate = adjustedDate
            return
        }

        eventsLoader.requireRefresh()

        val diff = adjustedDate.daysFromToday
        viewState.currentOrigin.x = diff.toFloat() * (-1f) * viewState.totalDayWidth
        invalidate()
    }

    private fun WeekViewViewState.getDateWithinDateRange(date: Calendar): Calendar {
        val minDate = minDate ?: date
        val maxDate = maxDate ?: date

        return if (date.isBefore(minDate)) {
            minDate
        } else if (date.isAfter(maxDate)) {
            maxDate + Days(1 - numberOfVisibleDays)
        } else if (numberOfVisibleDays >= 7 && showFirstDayOfWeekFirst) {
            val diff = computeDifferenceWithFirstDayOfWeek(date)
            date - Days(diff)
        } else {
            date
        }
    }

    /**
     * Refreshes the view and loads the events again.
     */
    fun notifyDataSetChanged() {
        eventsLoader.requireRefresh()
        invalidate()
    }

    /**
     * Scrolls to a specific hour.
     *
     * @param hour The hour to scroll to, in 24-hour format. Supported values are 0-24.
     *
     * @throws IllegalArgumentException Throws exception if the provided hour is smaller than
     *                                   [minHour] or larger than [maxHour].
     */
    fun goToHour(hour: Int) {
        if (viewState.hasBeenInvalidated) {
            // Perform navigation in next onDraw() call
            viewState.goToHour = hour
            return
        }

        if (hour !in viewState.timeRange) {
            throw IllegalArgumentException(
                "The provided hour ($hour) is outside of the set time range " +
                    "(${viewState.minHour} – ${viewState.maxHour})"
            )
        }

        val hourHeight = viewState.hourHeight
        val desiredOffset = hourHeight * (hour - viewState.minHour)

        // We make sure that WeekView doesn't "over-scroll" by limiting the offset to the total day
        // height minus the height of WeekView, which would result in scrolling all the way to the
        // bottom.
        val maxOffset = viewState.totalDayHeight - height
        val finalOffset = min(maxOffset, desiredOffset)

        viewState.currentOrigin.y = finalOffset * (-1)
        invalidate()
    }

    /**
     * Returns the first hour that is visible on the screen.
     */
    val firstVisibleHour: Double
        get() = (viewState.currentOrigin.y * -1 / viewState.hourHeight).toDouble()

    /*
     ***********************************************************************************************
     *
     *   Typeface
     *
     ***********************************************************************************************
     */

    /**
     * Returns the typeface used for events, time labels and date labels.
     */
    val typeface: Typeface
        get() = viewState.typeface
    // TODO
//        set(value) {
//            viewState.typeface = value
//            invalidate()
//        }

    /*
     ***********************************************************************************************
     *
     *   Listeners
     *
     ***********************************************************************************************
     */

    var onEventClickListener: OnEventClickListener<T>?
        get() = touchHandler.onEventClickListener
        set(value) {
            touchHandler.onEventClickListener = value
        }

    fun setOnEventClickListener(
        block: (data: T, rect: RectF) -> Unit
    ) {
        onEventClickListener = object : OnEventClickListener<T> {
            override fun onEventClick(data: T, eventRect: RectF) {
                block(data, eventRect)
            }
        }
    }

    var onMonthChangeListener: OnMonthChangeListener<T>?
        get() = (eventsLoader as? LegacyEventsLoader)?.onMonthChangeListener
        set(value) {
            eventsCacheWrapper.onListenerChanged(value)
            eventsLoaderWrapper.onListenerChanged(value)
        }

    fun setOnMonthChangeListener(
        block: (startDate: Calendar, endDate: Calendar) -> List<WeekViewDisplayable<T>>
    ) {
        onMonthChangeListener = object : OnMonthChangeListener<T> {
            override fun onMonthChange(
                startDate: Calendar,
                endDate: Calendar
            ): List<WeekViewDisplayable<T>> {
                return block(startDate, endDate)
            }
        }
    }

    /**
     * Submits a list of [WeekViewDisplayable]s to [WeekView]. If the new events fall into the
     * currently displayed date range, this method will also redraw [WeekView].
     */
    fun submit(items: List<WeekViewDisplayable<T>>) {
        eventsDiffer.submit(items) { shouldInvalidate ->
            if (shouldInvalidate) {
                invalidate()
            }
        }
    }

    var onLoadMoreListener: OnLoadMoreListener?
        get() = (eventsLoader as? PagedEventsLoader)?.onLoadMoreListener
        set(value) {
            eventsCacheWrapper.onListenerChanged(value)
            eventsLoaderWrapper.onListenerChanged(value)
        }

    /**
     * Registers a block that is called whenever [WeekView] needs to load more events. This is
     * similar to an [OnMonthChangeListener], but does not require anything to be returned.
     */
    fun setOnLoadMoreListener(
        block: (startDate: Calendar, endDate: Calendar) -> Unit
    ) {
        onLoadMoreListener = object : OnLoadMoreListener {
            override fun onLoadMore(startDate: Calendar, endDate: Calendar) {
                block(startDate, endDate)
            }
        }
    }

    var onEventLongClickListener: OnEventLongClickListener<T>?
        get() = touchHandler.onEventLongClickListener
        set(value) {
            touchHandler.onEventLongClickListener = value
        }

    fun setOnEventLongClickListener(
        block: (data: T, rect: RectF) -> Unit
    ) {
        onEventLongClickListener = object : OnEventLongClickListener<T> {
            override fun onEventLongClick(data: T, eventRect: RectF) {
                block(data, eventRect)
            }
        }
    }

    var onEmptyViewClickListener: OnEmptyViewClickListener?
        get() = touchHandler.onEmptyViewClickListener
        set(value) {
            touchHandler.onEmptyViewClickListener = value
        }

    fun setOnEmptyViewClickListener(
        block: (time: Calendar) -> Unit
    ) {
        onEmptyViewClickListener = object : OnEmptyViewClickListener {
            override fun onEmptyViewClicked(time: Calendar) {
                block(time)
            }
        }
    }

    var onEmptyViewLongClickListener: OnEmptyViewLongClickListener?
        get() = touchHandler.onEmptyViewLongClickListener
        set(value) {
            touchHandler.onEmptyViewLongClickListener = value
        }

    fun setOnEmptyViewLongClickListener(
        block: (time: Calendar) -> Unit
    ) {
        onEmptyViewLongClickListener = object : OnEmptyViewLongClickListener {
            override fun onEmptyViewLongClick(time: Calendar) {
                block(time)
            }
        }
    }

    var scrollListener: ScrollListener?
        get() = gestureHandler.scrollListener
        set(value) {
            gestureHandler.scrollListener = value
        }

    fun setScrollListener(
        block: (date: Calendar) -> Unit
    ) {
        scrollListener = object : ScrollListener {
            override fun onFirstVisibleDateChanged(date: Calendar) {
                block(checkNotNull(firstVisibleDate))
            }
        }
    }

    var onRangeChangeListener: OnRangeChangeListener? = null

    fun setOnRangeChangeListener(
        block: (firstVisibleDate: Calendar, lastVisibleDate: Calendar) -> Unit
    ) {
        onRangeChangeListener = object : OnRangeChangeListener {
            override fun onRangeChanged(firstVisibleDate: Calendar, lastVisibleDate: Calendar) {
                block(firstVisibleDate, lastVisibleDate)
            }
        }
    }

    private fun clearCaches() {
        drawers
            .filterIsInstance(CachingDrawer::class.java)
            .forEach { it.clear(viewState) }
    }

    override fun dispatchHoverEvent(
        event: MotionEvent
    ) = accessibilityTouchHelper?.dispatchHoverEvent(event) ?: super.dispatchHoverEvent(event)
}
