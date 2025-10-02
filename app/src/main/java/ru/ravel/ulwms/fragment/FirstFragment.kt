package ru.ravel.ulwms.fragment

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.launch
import ru.ravel.lcpecore.io.ProjectRepository
import ru.ravel.lcpecore.model.CoreProject
import ru.ravel.lcpecore.runtime.EngineRunner
import ru.ravel.ulwms.R
import ru.ravel.ulwms.service.ScriptLoader
import ru.ravel.ulwms.databinding.FragmentFirstBinding
import ru.ravel.ulwms.dto.SharedViewModel
import ru.ravel.ulwms.lcpe.CoreSubProjectRunnerAndroid
import java.io.File

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment() {

	private var binding: FragmentFirstBinding? = null
	private val viewModel: SharedViewModel by activityViewModels()

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		binding = FragmentFirstBinding.inflate(inflater, container, false)
		return binding!!.root
	}


	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		lifecycleScope.launch {
			val loader = ScriptLoader(requireActivity())
			val input: Map<String, Any?> = mapOf(
				"urlInput" to mapOf("url" to "https://api.2t2m.ru/api/v2/get_by_date"),
				"methodInput" to mapOf("method" to "POST"),
				"bodyInput" to mapOf("body" to "{\"date\": \"2024-4-16\"}"),
				"headersInput" to mapOf(
					"Authorization" to "Bearer-Token: MahDYc4FUXuQeFc0Y1gdkl8r",
					"Content-Type" to "application/json"
				),
			)
			val className = "ru.ravel.scripts.GroovyBlock_4_4388f9a26e3042079135ceb845f99bd6"
			loader.ensureDateAdderScript("https://dl.ravel57.ru/!fAu-rJzcg")
				.flatMapMaybe { dexFile: File ->
					Maybe.fromCallable { loader.loadInMemory(dexFile, input, className) }
						.subscribeOn(Schedulers.io())
				}
				.defaultIfEmpty(emptyMap())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(
					{ result -> binding?.textviewFirst?.text = result.toString() },
					{ err -> err.printStackTrace() }
				)
		}


		val project = CoreProject()
		val repo = object : ProjectRepository {
			override fun loadProject(absFile: File): CoreProject {
				return CoreProject()
			}

			override fun saveProject(absFile: File, project: CoreProject) {
				println()
			}
		}
		val runner = EngineRunner(
			groovy = null,
			python = null,
			js = null,
			subProjectRunner = CoreSubProjectRunnerAndroid(requireContext(), repo, null, null, null)
		)
		runner.run(project)


		viewModel.instruction.observe(viewLifecycleOwner) { instr ->
			binding!!.textviewFirst.text = instr
		}

		binding!!.buttonFirst.setOnClickListener {
			findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
		}
	}


	override fun onDestroyView() {
		super.onDestroyView()
		binding = null
	}
}