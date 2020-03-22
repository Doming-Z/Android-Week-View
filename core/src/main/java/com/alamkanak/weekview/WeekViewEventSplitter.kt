package com.alamkanak.weekview

internal class WeekViewEventSplitter<T>(
    private val viewState: WeekViewViewState
) {

    fun split(event: WeekViewEvent<T>): List<WeekViewEvent<T>> {
        if (event.startTime >= event.endTime) {
            return emptyList()
        }

        // Check whether the end date of the event is exactly 12 AM. If so, the event will be
        // shortened by a millisecond.
        val endsOnStartOfNextDay = event.endTime.isAtStartOfNextDay(event.startTime)
        val isAtStartOfNextPeriod = viewState.minHour == 0 && endsOnStartOfNextDay

        return when {
            isAtStartOfNextPeriod -> listOf(shortenTooLongAllDayEvent(event))
            event.isMultiDay -> splitEventByDates(event)
            else -> listOf(event)
        }
    }

    private fun shortenTooLongAllDayEvent(
        event: WeekViewEvent<T>
    ): WeekViewEvent<T> {
        val newEndTime = event.endTime.withTimeAtEndOfPeriod(viewState.maxHour)
        return event.copy(endTime = newEndTime)
    }

    private fun splitEventByDates(event: WeekViewEvent<T>): List<WeekViewEvent<T>> {
        val results = mutableListOf<WeekViewEvent<T>>()

        val firstEventEnd = event.startTime.withTimeAtEndOfPeriod(viewState.maxHour)
        val firstEvent = event.copy(endTime = firstEventEnd)
        results += firstEvent

        val lastEventStart = event.endTime.withTimeAtStartOfPeriod(viewState.minHour)
        val lastEvent = event.copy(startTime = lastEventStart)
        results += lastEvent

        val diff = lastEvent.startTime.timeInMillis - firstEvent.startTime.timeInMillis
        val daysInBetween = diff / Constants.DAY_IN_MILLIS

        if (daysInBetween > 0) {
            val start = firstEventEnd.withTimeAtStartOfPeriod(viewState.minHour) + Days(1)
            while (start.isSameDate(lastEventStart).not()) {
                val intermediateStart = start.withTimeAtStartOfPeriod(viewState.minHour)
                val intermediateEnd = start.withTimeAtEndOfPeriod(viewState.maxHour)
                results += event.copy(startTime = intermediateStart, endTime = intermediateEnd)
                start += Days(1)
            }
        }

        return results.sorted()
    }
}
