package com.shina.ashin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import java.io.File
import java.io.FileOutputStream

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [SettingFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class SettingFragment : Fragment() {
    // TODO: Rename and change types of parameters
    private var param1: String? = null
    private var param2: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)








        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?

    ): View {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_setting, container, false)



        val imageView = view.findViewById<ImageButton>(R.id.imageView)
        imageView.setOnClickListener {
            checkPermissions("1")


        }
        val imageView2 = view.findViewById<ImageButton>(R.id.imageView2)
        imageView2.setOnClickListener {
            checkPermissions("2")


        }
        val imageView3 = view.findViewById<ImageButton>(R.id.imageView3)
        imageView3.setOnClickListener {
            checkPermissions("3")


        }
        val imageView4 = view.findViewById<ImageButton>(R.id.imageView4)
        imageView4.setOnClickListener {
            checkPermissions("4")


        }
        return view
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment SettingFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            SettingFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }


    private fun checkPermissions(setting: String) {
        val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val writePermission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

        val permissionsToRequest = mutableListOf<String>()

        if (context?.checkSelfPermission(readPermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(readPermission)
        }

        if (context?.checkSelfPermission(writePermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(writePermission)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 1)
        } else {
            copyEBOOTToTelegramFolder(setting)
        }
    }


    private fun copyAssetToFile(context: Context,setting:String) {
        val assetManager = context.assets
        val inputStream = assetManager.open("$setting/EBOOT.OLD")
//        val dir = context.getExternalFilesDir("/PSP/PSP_GAME/SYSDIR")

        val dir =  File(Environment.getExternalStoragePublicDirectory("/PSP/PSP_GAME/SYSDIR"),"")
try {
    dir.mkdirs()
    val targetFile = File(dir, "EBOOT.OLD")
    val outputStream = FileOutputStream(targetFile)
    inputStream.copyTo(outputStream)
    inputStream.close()
    outputStream.close()
    Toast.makeText(context, "تنظیمات عکس $setting انتخاب شد ", Toast.LENGTH_SHORT).show()
}catch (e:Exception){
    Toast.makeText(context,e.message,Toast.LENGTH_SHORT).show()
}
    }


    private fun copyEBOOTToTelegramFolder(setting:String) {
        copyAssetToFile(requireContext(),setting)


    }


}

