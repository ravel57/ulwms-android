package ru.ravel.ulwms.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import ru.ravel.ulwms.activity.MainActivity
import kotlin.math.roundToInt

/**
 * Рендерит View-дерево из JSON.
 *
 * Поддерживает:
 *  type: LinearLayout | TextView | Button | FrameLayout (базово)
 *
 * Общие атрибуты (где уместно):
 *  - id (string)            : логический идентификатор (кладётся в view.tag)
 *  - width (wrap|match|dp)  : например "match", "wrap", 120
 *  - height (wrap|match|dp)
 *  - margin / padding (dp)  : число или объект {left,top,right,bottom}
 *  - background ("#RRGGBB" | "#AARRGGBB")
 *  - gravity ("center","start","end","center_vertical","center_horizontal")
 *
 * LinearLayout:
 *  - orientation ("vertical"|"horizontal")
 *  - weightSum (number)
 *  - children (Array)
 *
 * Для ребёнка в LinearLayout:
 *  - weight (number)
 *
 * TextView:
 *  - text (string)
 *  - textSizeSp (number)
 *
 * Button:
 *  - text (string)
 *  - action (string)   -> ищется в actions по ключу
 */
object JsonUiRenderer {

	fun render(
		context: Context,
		json: JSONObject,
		actions: Map<String, () -> Unit> = emptyMap()
	): View {
		return createView(context, json, null, actions)
	}

	fun renderArrayInto(
		context: Context,
		container: ViewGroup,
		jsonArray: JSONArray,
		actions: Map<String, () -> Unit> = emptyMap()
	) {
		container.removeAllViews()
		for (i in 0 until jsonArray.length()) {
			val child = jsonArray.getJSONObject(i)
			container.addView(createView(context, child, container, actions))
		}
	}

	fun getTypeByTag(
		context: Context,
		spec: JSONObject,
	): Class<out View> {
		return createView(context, spec, null, mapOf())::class.java
	}

	// ---------- Внутренности ----------

	@SuppressLint("SetTextI18n")
	private fun createView(
		context: Context,
		spec: JSONObject,
		parent: ViewGroup?,
		actions: Map<String, () -> Unit>
	): View {
		val view: View = when (val type = spec.optString("type")) {
			"LinearLayout" -> createLinearLayout(context, spec, actions)
			"TextView" -> createTextView(context, spec)
			"Button" -> createButton(context, spec, actions)
			"FrameLayout" -> FrameLayout(context)
			"ScrollView" -> createScrollView(context, spec, actions)
			"RecyclerView" -> createRecyclerView(context, spec, actions)
			"EditText" -> createEditText(context, spec)
			"Spinner" -> createSpinner(context, spec, actions)
			"HorizontalLayout" -> createHorizontalLayout(context, spec, actions)
			"CheckBox" -> createCheckBox(context, spec, actions)
			"RadioButton" -> createRadioButton(context, spec, actions)
			"RadioGroup" -> createRadioGroup(context, spec, actions)
			"SubmitButton" -> createSubmitButton(context, spec, actions)
			"FAB" -> commandFab(context, spec)
			else -> TextView(context).apply {
				text = "Unknown type: $type"
				setTextColor(Color.RED)
			}
		}
		// tag как "id" из JSON
		spec.optString("id").takeIf { it.isNotBlank() }?.let { view.tag = it }

		// Общие атрибуты
		applyBackground(view, spec)
		applyPadding(view, spec)

		// LayoutParams (зависят от родителя)
		val lp = buildLayoutParams(parent, spec)
		view.layoutParams = lp

		// Специфические атрибуты
		(view as? LinearLayout)?.let { applyLinearLayoutAttrs(it, spec) }
		applyGravity(view, spec, parent)

		return view
	}


	private fun createLinearLayout(
		context: Context,
		spec: JSONObject,
		actions: Map<String, () -> Unit>
	): LinearLayout {
		val ll = LinearLayout(context).apply {
			orientation = when (spec.optString("orientation", "vertical")) {
				"horizontal" -> LinearLayout.HORIZONTAL
				else -> LinearLayout.VERTICAL
			}
			if (spec.has("weightSum")) {
				weightSum = spec.optDouble("weightSum", 0.0).toFloat()
			}
		}
		val children = spec.optJSONArray("children")
		if (children != null) {
			for (i in 0 until children.length()) {
				val childSpec = children.getJSONObject(i)
				val childView = createView(context, childSpec, ll, actions)
				ll.addView(childView, buildLayoutParams(ll, childSpec))
			}
		}
		return ll
	}


	private fun createTextView(context: Context, spec: JSONObject): TextView {
		return TextView(context).apply {
			text = spec.optString("text", "")
			val sp = spec.optDouble("textSizeSp", 16.0)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
		}
	}


	private fun createButton(
		context: Context,
		spec: JSONObject,
		actions: Map<String, () -> Unit>
	): Button {
		return Button(context).apply {
			text = spec.optString("text", "Button")
			val actionKey = spec.optString("action", null)
			setOnClickListener {
				val act = actionKey.let { actions[it] }
				if (act != null) act() else Toast.makeText(
					context, "Нет обработчика для action='$actionKey'", Toast.LENGTH_SHORT
				).show()
			}
		}
	}


	private fun createScrollView(
		context: Context,
		spec: JSONObject,
		actions: Map<String, () -> Unit>
	): ScrollView {
		val sv = ScrollView(context)
		// ScrollView может содержать только одного прямого ребёнка
		val childSpec = spec.optJSONArray("children")
		if (childSpec != null && childSpec.length() > 0) {
			// Обычно внутрь кладут LinearLayout
			val firstChild = childSpec.getJSONObject(0)
			val childView = createView(context, firstChild, sv, actions)
			sv.addView(childView, buildLayoutParams(sv, firstChild))
		}
		return sv
	}


	private fun createRecyclerView(
		context: Context,
		spec: JSONObject,
		actions: Map<String, () -> Unit>
	): RecyclerView {
		val rv = RecyclerView(context)
		// layoutManager
		val orientation = when (spec.optString("orientation", "vertical")) {
			"horizontal" -> RecyclerView.HORIZONTAL
			else -> RecyclerView.VERTICAL
		}
		rv.layoutManager = LinearLayoutManager(context, orientation, false)
		// Данные
		val items = mutableListOf<String>()
		val arr = spec.optJSONArray("items")
		if (arr != null) {
			for (i in 0 until arr.length()) {
				items.add(arr.getString(i))
			}
		}
		rv.adapter = SimpleStringAdapter(items)
		return rv
	}


	class SimpleStringAdapter(private val items: List<String>) :
		RecyclerView.Adapter<SimpleStringAdapter.VH>() {

		class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
			val tv = TextView(parent.context).apply {
				setPadding(32, 16, 32, 16)
				textSize = 16f
			}
			return VH(tv)
		}

		override fun onBindViewHolder(holder: VH, position: Int) {
			holder.textView.text = items[position]
		}

		override fun getItemCount(): Int = items.size
	}


	private fun buildLayoutParams(parent: ViewGroup?, spec: JSONObject): ViewGroup.LayoutParams {
		val width = parseSize(spec.opt("width"), ViewGroup.LayoutParams.WRAP_CONTENT)
		val height = parseSize(spec.opt("height"), ViewGroup.LayoutParams.WRAP_CONTENT)

		val base: ViewGroup.MarginLayoutParams =
			if (parent is LinearLayout) {
				val lp = LinearLayout.LayoutParams(width, height)
				if (spec.has("weight")) {
					lp.weight = spec.optDouble("weight", 0.0).toFloat()
				}
				lp
			} else {
				ViewGroup.MarginLayoutParams(width, height)
			}

		applyMargin(base, spec)
		return base
	}


	private fun applyMargin(lp: ViewGroup.MarginLayoutParams, spec: JSONObject) {
		val margin = spec.opt("margin")
		if (margin is Number) {
			val m = dp(margin.toFloat())
			lp.setMargins(m, m, m, m)
		} else if (margin is JSONObject) {
			lp.setMargins(
				dp(margin.optDouble("left", 0.0).toFloat()),
				dp(margin.optDouble("top", 0.0).toFloat()),
				dp(margin.optDouble("right", 0.0).toFloat()),
				dp(margin.optDouble("bottom", 0.0).toFloat())
			)
		}
	}


	private fun applyPadding(view: View, spec: JSONObject) {
		val padding = spec.opt("padding")
		if (padding is Number) {
			val p = dp(padding.toFloat())
			view.setPadding(p, p, p, p)
		} else if (padding is JSONObject) {
			view.setPadding(
				dp(padding.optDouble("left", 0.0).toFloat()),
				dp(padding.optDouble("top", 0.0).toFloat()),
				dp(padding.optDouble("right", 0.0).toFloat()),
				dp(padding.optDouble("bottom", 0.0).toFloat())
			)
		}
	}


	private fun applyBackground(view: View, spec: JSONObject) {
		val bg = spec.optString("background", "")
		if (bg.isNotBlank()) {
			try {
				view.setBackgroundColor(Color.parseColor(bg))
			} catch (_: Throwable) {
			}
		}
	}


	private fun applyLinearLayoutAttrs(ll: LinearLayout, spec: JSONObject) {
		// пока только orientation и weightSum заданы выше
	}


	private fun applyGravity(view: View, spec: JSONObject, parent: ViewGroup?) {
		val g = spec.optString("gravity", "")
		if (g.isBlank()) return

		fun parseGravity(s: String): Int = when (s) {
			"center" -> Gravity.CENTER
			"start" -> Gravity.START
			"end" -> Gravity.END
			"center_vertical" -> Gravity.CENTER_VERTICAL
			"center_horizontal" -> Gravity.CENTER_HORIZONTAL
			"top" -> Gravity.TOP
			"bottom" -> Gravity.BOTTOM
			else -> Gravity.NO_GRAVITY
		}

		val mask = g.split('|').map { it.trim() }.fold(0) { acc, part -> acc or parseGravity(part) }
		// Для LinearLayout.LayoutParams можно задать gravity у LayoutParams
		val lp = view.layoutParams
		if (lp is LinearLayout.LayoutParams) {
			lp.gravity = mask
			view.layoutParams = lp
		} else if (view is FrameLayout) {
			view.foregroundGravity = mask
		}
	}


	private fun parseSize(v: Any?, default: Int): Int {
		return when (v) {
			null -> default
			is String -> when (v) {
				"wrap" -> ViewGroup.LayoutParams.WRAP_CONTENT
				"match" -> ViewGroup.LayoutParams.MATCH_PARENT
				else -> dp(v.toFloatOrNull() ?: 0f)
			}

			is Number -> dp(v.toFloat())
			else -> default
		}
	}


	private fun dp(value: Float): Int {
		// context нет под рукой; используем mdpi=160dp базово: применим стандартный способ через  Resources?
		// Здесь сделаем универсальный пересчёт через системные плотности:
		val density = android.content.res.Resources.getSystem().displayMetrics.density
		return (value * density).roundToInt()
	}


	private fun createEditText(context: Context, spec: JSONObject): EditText {
		return EditText(context).apply {
			hint = spec.optString("hint", "")
			setText(spec.optString("text", ""))

			// Размер шрифта
			if (spec.has("textSizeSp")) {
				val sp = spec.optDouble("textSizeSp", 16.0)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
			}

			// Цвет текста
			if (spec.has("textColor")) {
				try {
					setTextColor(Color.parseColor(spec.getString("textColor")))
				} catch (_: Throwable) {
				}
			}

			// Подсказка серым
			if (spec.has("hintColor")) {
				try {
					setHintTextColor(Color.parseColor(spec.getString("hintColor")))
				} catch (_: Throwable) {
				}
			}

			// inputType (например "text", "number", "password", "email")
			inputType = when (spec.optString("inputType", "text")) {
				"number" -> InputType.TYPE_CLASS_NUMBER
				"password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
				"email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
				else -> InputType.TYPE_CLASS_TEXT
			}

			// padding и фон для красоты
			setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
			setBackgroundColor(Color.parseColor(spec.optString("background", "#FFFFFF")))
		}
	}


	private fun createSpinner(
		context: Context,
		spec: JSONObject,
		actions: Map<String, () -> Unit>
	): Spinner {
		val spinner = Spinner(context)

		// Получаем элементы из массива options
		val items = mutableListOf<String>()
		val options = spec.optJSONArray("options")
		if (options != null) {
			for (i in 0 until options.length()) {
				items.add(options.getString(i))
			}
		}

		// Создаём адаптер
		val adapter = ArrayAdapter(
			context,
			android.R.layout.simple_spinner_dropdown_item,
			items
		)
		spinner.adapter = adapter

		// Устанавливаем выбранный элемент (если указан)
		val selected = spec.optString("selected", "")
		if (selected.isNotBlank()) {
			val index = items.indexOf(selected)
			if (index >= 0) spinner.setSelection(index)
		}

		// Можно задать id, чтобы потом получить выбранный пункт
		val id = spec.optString("id")
		if (id.isNotBlank()) spinner.tag = id

		// Обработчик выбора — если задан action
		val actionKey = spec.optString("action", null)
		if (actionKey != null && actions.containsKey(actionKey)) {
			spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
				override fun onItemSelected(
					parent: AdapterView<*>,
					view: View?,
					position: Int,
					id: Long
				) {
					actions[actionKey]?.invoke()
				}

				override fun onNothingSelected(parent: AdapterView<*>) {}
			}
		}

		return spinner
	}


	private fun createHorizontalLayout(
		context: Context,
		spec: JSONObject,
		actions: Map<String, () -> Unit>
	): LinearLayout {
		val ll = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			if (spec.has("weightSum")) {
				weightSum = spec.optDouble("weightSum", 0.0).toFloat()
			}
		}

		val children = spec.optJSONArray("children")
		if (children != null) {
			for (i in 0 until children.length()) {
				val childSpec = children.getJSONObject(i)
				val childView = createView(context, childSpec, ll, actions)
				ll.addView(childView, buildLayoutParams(ll, childSpec))
			}
		}

		return ll
	}


	private fun createCheckBox(
		context: Context,
		spec: JSONObject,
		actions: Map<String, () -> Unit>
	): CheckBox {
		return CheckBox(context).apply {
			text = spec.optString("text", "")
			isChecked = spec.optBoolean("checked", false)

			// Цвет текста
			if (spec.has("textColor")) {
				try {
					setTextColor(Color.parseColor(spec.getString("textColor")))
				} catch (_: Throwable) {
				}
			}

			// id для доступа через tag
			val id = spec.optString("id")
			if (id.isNotBlank()) tag = id

			// действие при клике
			val actionKey = spec.optString("action", null)
			if (actionKey != null && actions.containsKey(actionKey)) {
				setOnCheckedChangeListener { _, _ ->
					actions[actionKey]?.invoke()
				}
			}
		}
	}


	private fun createRadioButton(
		context: Context,
		spec: JSONObject,
		actions: Map<String, () -> Unit>
	): RadioButton {
		return RadioButton(context).apply {
			text = spec.optString("text", "")
			isChecked = spec.optBoolean("checked", false)

			// Цвет текста
			if (spec.has("textColor")) {
				try {
					setTextColor(Color.parseColor(spec.getString("textColor")))
				} catch (_: Throwable) {
				}
			}

			// id
			val id = spec.optString("id")
			if (id.isNotBlank()) tag = id

			// действие при выборе
			val actionKey = spec.optString("action", null)
			if (actionKey != null && actions.containsKey(actionKey)) {
				setOnCheckedChangeListener { _, isChecked ->
					if (isChecked) actions[actionKey]?.invoke()
				}
			}
		}
	}


	private fun createRadioGroup(
		context: Context,
		spec: JSONObject,
		actions: Map<String, () -> Unit>
	): RadioGroup {
		val rg = RadioGroup(context).apply {
			orientation = when (spec.optString("orientation", "vertical")) {
				"horizontal" -> RadioGroup.HORIZONTAL
				else -> RadioGroup.VERTICAL
			}
		}
		// Задаём id/тег, если есть
		val id = spec.optString("id")
		if (id.isNotBlank()) rg.tag = id
		// Добавляем всех детей (RadioButton-ов)
		val children = spec.optJSONArray("children")
		if (children != null) {
			for (i in 0 until children.length()) {
				val childSpec = children.getJSONObject(i)
				val rb = createRadioButton(context, childSpec, actions)
				rg.addView(rb, buildLayoutParams(rg, childSpec))
			}
		}
		// Обработчик смены выбранной кнопки
		val actionKey = spec.optString("action", null)
		if (actionKey != null && actions.containsKey(actionKey)) {
			rg.setOnCheckedChangeListener { _, _ ->
				actions[actionKey]?.invoke()
			}
		}
		return rg
	}


	private fun createSubmitButton(
		context: Context,
		spec: JSONObject,
		actions: Map<String, () -> Unit>
	): Button {
		return Button(context).apply {
			text = spec.optString("text", "Submit")
			val submitKey = spec.optString("submitAction", null)
			setOnClickListener {
				if (submitKey != null && actions.containsKey(submitKey)) {
					actions[submitKey]?.invoke()  // 🔥 вызывает только submitFromGroovy
				} else {
					Toast.makeText(context, "Нет submitAction='$submitKey'", Toast.LENGTH_SHORT)
						.show()
				}
			}
		}
	}


	private fun commandFab(context: Context, spec: JSONObject): View {
		val act = MainActivity.instance ?: return View(context)

		act.runOnUiThread {
			val fab = act.binding.fab
			fab.visibility = View.VISIBLE

			// Иконка
			val icon = spec.optString("icon", "")
			if (icon.isNotBlank()) {
				val resId = context.resources.getIdentifier(icon, "drawable", context.packageName)
				if (resId != 0) fab.setImageResource(resId)
			}

			// Цвет
			if (spec.has("background")) {
				try {
					fab.backgroundTintList =
						android.content.res.ColorStateList.valueOf(Color.parseColor(spec.getString("background")))
				} catch (_: Throwable) {}
			}
		}

		// Возвращаем невидимую заглушку, чтобы JSON-дерево не ломалось
		return View(context).apply { visibility = View.GONE }
	}


}
