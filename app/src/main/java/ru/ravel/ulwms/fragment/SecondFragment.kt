package ru.ravel.ulwms.fragment

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import ru.ravel.ulwms.R
import ru.ravel.ulwms.databinding.FragmentSecondBinding
import ru.ravel.ulwms.dto.SharedViewModel

/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class SecondFragment : Fragment() {

	private var binding: FragmentSecondBinding? = null
	private val viewModel: SharedViewModel by activityViewModels()

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		binding = FragmentSecondBinding.inflate(inflater, container, false)
		return binding!!.root

	}


	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		viewModel.instruction.observe(viewLifecycleOwner) { instr ->
			binding!!.textviewSecond.text = instr
		}

		binding!!.buttonSecond.setOnClickListener {
			findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
		}
	}


	override fun onDestroyView() {
		super.onDestroyView()
		binding = null
	}
}