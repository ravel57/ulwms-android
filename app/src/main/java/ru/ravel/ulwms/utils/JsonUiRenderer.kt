package ru.ravel.ulwms.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
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

	// ---------- Внутренности ----------

	@SuppressLint("SetTextI18n")
	private fun createView(
		context: Context,
		spec: JSONObject,
		parent: ViewGroup?,
		actions: Map<String, () -> Unit>
	): View {
		val type = spec.optString("type")
		val view: View = when (type) {
			"LinearLayout" -> createLinearLayout(context, spec, actions)
			"TextView" -> createTextView(context, spec)
			"Button" -> createButton(context, spec, actions)
			"FrameLayout" -> FrameLayout(context)
			"ScrollView" -> createScrollView(context, spec, actions)
			"RecyclerView" -> createRecyclerView(context, spec, actions)
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


	// ---------- Примеси / утилиты ----------

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
}
