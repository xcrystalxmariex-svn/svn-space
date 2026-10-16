package space.svn.svnspace.ui.tree

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import space.svn.svnspace.databinding.FragmentSearchBinding

/**
 * Unified search entry across local watched folders and connected remotes.
 * Full-text / name search can be expanded later; this provides the UI shell and local name filter.
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnSearch.setOnClickListener {
            val q = binding.editQuery.text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) {
                Toast.makeText(requireContext(), "Enter a search term", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Placeholder – full cross-provider search will walk local + remote listings
            binding.txtResults.text = "Search for \"$q\" across all connections and watched folders.\n(Full indexed search can be added on top of the existing tree walkers.)"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
