package com.fimtale.modules;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import com.fimtale.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.*;
import com.google.gson.JsonObject;
import java.util.*;

/** Retains ordinary form inputs on rotation; never saves passwords or mail verification tokens. */
public class ModuleFormDialog extends DialogFragment {
    public static final String TAG = "native_module_form";
    private final Map<String, EditText> inputs = new LinkedHashMap<>();
    private final Map<String, CheckBox> checks = new LinkedHashMap<>();
    private boolean waiting;
    private String session;
    public static Bundle field(String key, int label, String value, int type) {
        Bundle b = new Bundle(); b.putString("key", key); b.putInt("label", label); b.putString("value", value); b.putInt("type", type); return b;
    }
    public static Bundle field(String key, int label, String value) { return field(key, label, value, InputType.TYPE_CLASS_TEXT); }
    public static Bundle check(String key, int label, boolean value) {
        Bundle b = field(key, label, Boolean.toString(value)); b.putBoolean("check", true); return b;
    }
    public static void show(ModuleActivity activity, String key, int title, Bundle[] fields) {
        Bundle b = new Bundle(); b.putString("key", key); b.putInt("title", title);
        // Account ID is sufficient for form ownership. Session tokens never enter a saved Bundle.
        b.putString("owner", com.fimtale.utils.UserPreferences.getUserId(activity));
        b.putParcelableArrayList("fields", new ArrayList<>(Arrays.asList(fields)));
        ModuleFormDialog dialog = new ModuleFormDialog(); dialog.setArguments(b); dialog.show(activity.getSupportFragmentManager(), TAG);
    }
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        ModuleActivity activity = (ModuleActivity) requireActivity();
        inputs.clear(); checks.clear(); session = activity.model.session();
        waiting = state != null && state.getBoolean("waiting");
        LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0);
        ArrayList<Bundle> fields = requireArguments().getParcelableArrayList("fields");
        for (Bundle b : fields) {
            String key = b.getString("key");
            if (b.getBoolean("check")) {
                CheckBox v = new CheckBox(activity); v.setText(b.getInt("label")); v.setChecked(state != null ? state.getBoolean(key, Boolean.parseBoolean(b.getString("value"))) : Boolean.parseBoolean(b.getString("value")));
                checks.put(key, v); column.addView(v);
            } else {
                TextInputLayout box = new TextInputLayout(activity); box.setHint(getString(b.getInt("label"))); box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED);
                TextInputEditText v = new TextInputEditText(box.getContext()); v.setInputType(b.getInt("type")); v.setSaveEnabled(false);
                if (secret(key)) v.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
                String value = !secret(key) && state != null ? state.getString(key, b.getString("value")) : b.getString("value");
                v.setText(value); if ((b.getInt("type") & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) { v.setMinLines(3); v.setMaxLines(6); }
                box.addView(v); column.addView(box); inputs.put(key, v);
            }
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(column);
        return new MaterialAlertDialogBuilder(activity).setTitle(requireArguments().getInt("title")).setView(scroll)
                .setNegativeButton(R.string.common_cancel, null).setPositiveButton(R.string.common_confirm, null).create();
    }
    private boolean secret(String key) { return key.contains("password") || key.equals("token"); }
    @Override public void onStart() {
        super.onStart(); ModuleActivity activity = (ModuleActivity) requireActivity();
        activity.model.changes.removeObservers(this);
        activity.model.changes.observe(this, ignored -> updateState());
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ModuleActivity host = (ModuleActivity) requireActivity();
            if (!owned(host)) { dismiss(); return; }
            if (host.model.busy()) return;
            JsonObject values = new JsonObject(); inputs.forEach((key, input) -> values.addProperty(key, input.getText().toString()));
            checks.forEach((key, input) -> values.addProperty(key, input.isChecked()));
            if (host.onForm(requireArguments().getString("key"), values)) {
                waiting = host.model.busy();
                if (!waiting) dismiss(); else updateState();
            }
        });
        updateState();
    }
    private boolean owned(ModuleActivity host) {
        return requireArguments().getString("owner", "").equals(com.fimtale.utils.UserPreferences.getUserId(host))
                && host.model.sameSession(session);
    }
    private void updateState() {
        if (!isAdded() || getDialog() == null) return;
        ModuleActivity host = (ModuleActivity) requireActivity();
        if (!owned(host)) { dismissAllowingStateLoss(); return; }
        boolean busy = host.model.busy();
        if (waiting && !busy) {
            waiting = false;
            if (host.model.mutationSucceeded) { dismissAllowingStateLoss(); return; }
            String error = host.model.errors.get("mutation");
            if (error == null) error = host.model.errors.get("user_lookup");
            if (error != null && !inputs.isEmpty()) inputs.values().iterator().next().setError(error);
        }
        inputs.values().forEach(input -> input.setEnabled(!busy));
        checks.values().forEach(input -> input.setEnabled(!busy));
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
        }
        setCancelable(!busy);
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        out.putBoolean("waiting", waiting);
        super.onSaveInstanceState(out); inputs.forEach((key, input) -> { if (!secret(key)) out.putString(key, input.getText().toString()); });
        checks.forEach((key, input) -> out.putBoolean(key, input.isChecked()));
    }
}
