package ru.ravel.ulwms.model

enum class LayoutAction(
	val action: String
) {
	OPEN_FORM("openForm"),
	BACK_TO_MAIN("backToMain"),
	REFRESH_HOLIDAYS("refreshHolidays"),
	PRESSED_GOOD("pressedGood"),
	PRESSED_BAD("pressedBad"),
	;
}