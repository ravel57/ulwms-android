package ru.ravel.ulwms.dto

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
	val instruction = MutableLiveData<String>()
}