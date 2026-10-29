package com.lagradost.cloudstream3.ui.setup

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentSetupExtensionsBinding
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.OpenstreamRepoManager
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding

class SetupFragmentExtensions : BaseFragment<FragmentSetupExtensionsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentSetupExtensionsBinding::inflate)
) {
    companion object {
        const val SETUP_EXTENSION_BUNDLE_IS_SETUP = "isSetup"

        /**
         * If false then this is treated as a singular screen with a done button
         */
        fun newInstance(isSetup: Boolean): Bundle {
            return Bundle().apply {
                putBoolean(SETUP_EXTENSION_BUNDLE_IS_SETUP, isSetup)
            }
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
    }

    override fun onBindingCreated(binding: FragmentSetupExtensionsBinding) {
        val isSetup = arguments?.getBoolean(SETUP_EXTENSION_BUNDLE_IS_SETUP) ?: false

        safe {
            binding.apply {
                if (!isSetup) {
                    nextBtt.setText(R.string.setup_done)
                }
                prevBtt.isVisible = isSetup

                nextBtt.setOnClickListener {
                    navigateNext(isSetup)
                }

                prevBtt.setOnClickListener {
                    findNavController().navigate(R.id.navigation_setup_language)
                }

                retryBtt.setOnClickListener {
                    startAutomatedRepoSetup(isSetup)
                }
            }

            startAutomatedRepoSetup(isSetup)
        }
    }

    private fun startAutomatedRepoSetup(isSetup: Boolean) {
        val act = activity ?: return
        val binding = binding ?: return

        binding.progressContainer.isVisible = true
        binding.errorContainer.isVisible = false
        binding.nextBtt.isEnabled = false
        binding.setupProgressBar.progress = 0
        binding.setupProgressText.text = getString(R.string.loading)

        ioSafe {
            val result = OpenstreamRepoManager.setupDefaultRepository(act) { statusText, progress ->
                main {
                    binding.setupProgressText.text = statusText
                    binding.setupProgressBar.progress = progress
                }
            }

            main {
                if (result.isSuccess) {
                    binding.progressContainer.isVisible = true
                    binding.errorContainer.isVisible = false
                    binding.nextBtt.isEnabled = true
                    binding.setupProgressText.text = "Setup completed successfully!"

                    // Automatically continue to next screen after setup completes
                    setKey(HAS_DONE_SETUP_KEY, true)
                    navigateNext(isSetup)
                } else {
                    binding.progressContainer.isVisible = false
                    binding.errorContainer.isVisible = true
                    val errorMsg = result.exceptionOrNull()?.localizedMessage
                        ?: "Failed to download Openstream repository. Please check your internet connection."
                    binding.errorText.text = errorMsg
                }
            }
        }
    }

    private fun navigateNext(isSetup: Boolean) {
        safe {
            setKey(HAS_DONE_SETUP_KEY, true)
            if (isSetup) {
                if (apis.distinctBy { it.lang }.size > 1) {
                    findNavController().navigate(R.id.action_navigation_setup_extensions_to_navigation_setup_provider_languages)
                } else {
                    findNavController().navigate(R.id.action_navigation_setup_extensions_to_navigation_setup_media)
                }
            } else {
                findNavController().navigate(R.id.navigation_home)
            }
        }
    }
}
