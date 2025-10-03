package ru.ravel.ulwms.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import ru.ravel.ulwms.R
import ru.ravel.ulwms.databinding.FragmentFirstBinding
import ru.ravel.ulwms.dto.SharedViewModel


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


	@SuppressLint("SdCardPath")
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

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