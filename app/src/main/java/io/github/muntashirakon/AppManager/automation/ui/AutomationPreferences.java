// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.automation.android.AndroidCallerVerifier;
import io.github.muntashirakon.AppManager.automation.android.AndroidGrantRepository;
import io.github.muntashirakon.AppManager.automation.core.AutomationCaller;
import io.github.muntashirakon.AppManager.automation.core.AutomationGrant;
import io.github.muntashirakon.AppManager.automation.core.AutomationRequest;
import io.github.muntashirakon.AppManager.profiles.ProfileManager;
import io.github.muntashirakon.AppManager.profiles.struct.BaseProfile;
import io.github.muntashirakon.AppManager.settings.PreferenceFragment;

/**
 * Locally authenticated, opt-in consent/revocation UI.
 *
 * This Fragment is navigated to only from SettingsActivity, which inherits
 * BaseActivity authentication. No Intent or Binder extra can create a grant.
 * The runtime IPC endpoint remains DISABLED in this milestone.
 */
public final class AutomationPreferences extends PreferenceFragment {
    private static final String[] TASKER_PACKAGES = {
            "net.dinglisch.android.taskerm", "net.dinglisch.android.tasker"
    };
    private static final long GRANT_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    private AndroidGrantRepository grantRepository;
    private AndroidCallerVerifier verifier;
    private PreferenceCategory approvals;
    private Preference status;

    @Override
    public void onCreatePreferences(@Nullable Bundle state, @Nullable String rootKey) {
        grantRepository = new AndroidGrantRepository(requireContext());
        verifier = new AndroidCallerVerifier(requireContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        PreferenceCategory actions = new PreferenceCategory(requireContext());
        actions.setTitle(R.string.automation_consent_heading);
        screen.addPreference(actions);
        status = new Preference(requireContext());
        status.setTitle(R.string.automation_runtime_unavailable);
        status.setSummary(R.string.automation_runtime_unavailable_summary);
        status.setSelectable(false);
        actions.addPreference(status);

        Preference read = new Preference(requireContext());
        read.setTitle(R.string.automation_approve_capabilities);
        read.setSummary(R.string.automation_approve_capabilities_summary);
        read.setOnPreferenceClickListener(item -> {
            chooseVerifiedTasker(caller -> approve(caller,
                    AutomationRequest.QUERY_CAPABILITIES, null, null));
            return true;
        });
        actions.addPreference(read);

        Preference profile = new Preference(requireContext());
        profile.setTitle(R.string.automation_approve_profile);
        profile.setSummary(R.string.automation_approve_profile_summary);
        profile.setOnPreferenceClickListener(item -> {
            chooseVerifiedTasker(this::chooseProfile);
            return true;
        });
        actions.addPreference(profile);

        approvals = new PreferenceCategory(requireContext());
        approvals.setTitle(R.string.automation_current_grants);
        screen.addPreference(approvals);
    }

    @Override
    public void onStart() {
        super.onStart();
        refreshGrants();
    }

    @Override
    public int getTitle() {
        return R.string.automation_title;
    }

    private interface IdentitySelection {
        void onSelected(AutomationCaller caller);
    }

    private void chooseVerifiedTasker(IdentitySelection selected) {
        List<AutomationCaller> installed = new ArrayList<>();
        for (String name : TASKER_PACKAGES) {
            AutomationCaller caller = verifier.verifyInstalledForConsent(name);
            if (caller != null) installed.add(caller);
        }
        if (installed.isEmpty()) {
            showMessage(getString(R.string.automation_tasker_not_installed));
            return;
        }
        if (installed.size() == 1) {
            selected.onSelected(installed.get(0));
            return;
        }
        CharSequence[] choices = new CharSequence[installed.size()];
        for (int i = 0; i < installed.size(); ++i) choices[i] = installed.get(i).packageName;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.automation_choose_tasker)
                .setItems(choices, (dialog, which) -> selected.onSelected(installed.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseProfile(AutomationCaller caller) {
        // Profile definition I/O must not block the Settings UI thread.
        new Thread(() -> {
            List<BaseProfile> profiles = new ArrayList<>();
            try {
                profiles.addAll(ProfileManager.getProfiles());
                Collections.sort(profiles, (left, right) -> left.name.compareToIgnoreCase(right.name));
            } catch (Exception ignored) {
                // No implicit success or grant after corrupt/missing profiles.
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (profiles.isEmpty()) {
                    showMessage(getString(R.string.automation_no_profiles));
                    return;
                }
                CharSequence[] choices = new CharSequence[profiles.size()];
                for (int i = 0; i < profiles.size(); ++i) {
                    choices[i] = profiles.get(i).name;
                }
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.automation_choose_profile)
                        .setItems(choices, (dialog, which) -> {
                            BaseProfile chosen = profiles.get(which);
                            approve(caller, AutomationRequest.APPLY_PROFILE,
                                    chosen.profileId, chosen.name);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }, "am-automation-profiles").start();
    }

    private void approve(AutomationCaller initialCaller, String operation,
                         @Nullable String profileId, @Nullable String profileName) {
        if (!isAdded()) return;
        final String title = getString(R.string.automation_confirm_approval);
        String scope = operation + (profileName != null ? " / " + profileName : "");
        String explanation = getString(R.string.automation_approval_explanation,
                initialCaller.packageName, scope);
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(explanation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.automation_approve, (dialog, which) -> {
                    // Always re-check installed UID and signer immediately before commit.
                    AutomationCaller current = verifier.verifyInstalledForConsent(initialCaller.packageName);
                    if (current == null || current.uid != initialCaller.uid
                            || !current.packageName.equals(initialCaller.packageName)
                            || !current.signingCertSha256.equals(initialCaller.signingCertSha256)) {
                        showMessage(getString(R.string.automation_identity_changed));
                        return;
                    }
                    try {
                        long now = System.currentTimeMillis();
                        long expiry = now + GRANT_TTL_MILLIS;
                        if (expiry <= now) throw new IllegalArgumentException("invalid expiry");
                        AutomationGrant grant = new AutomationGrant(current.uid,
                                current.packageName, current.signingCertSha256,
                                operation, profileId, expiry);
                        grantRepository.putUserApprovedGrant(grant);
                        refreshGrants();
                    } catch (IOException | IllegalArgumentException error) {
                        showMessage(getString(R.string.automation_store_error));
                    }
                })
                .show();
    }

    private void refreshGrants() {
        if (approvals == null || !isAdded()) return;
        approvals.removeAll();
        final List<AutomationGrant> grants;
        try {
            grants = grantRepository.listForUser();
        } catch (IOException error) {
            // Corrupt grant storage is an explicit error, never an empty/approved state.
            Preference failed = new Preference(requireContext());
            failed.setTitle(R.string.automation_store_error);
            failed.setSelectable(false);
            approvals.addPreference(failed);
            return;
        }
        if (grants.isEmpty()) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.automation_no_grants);
            empty.setSelectable(false);
            approvals.addPreference(empty);
            return;
        }
        for (AutomationGrant grant : grants) {
            Preference item = new Preference(requireContext());
            item.setTitle(grant.packageName + " · " + grant.operation);
            item.setSummary(getString(R.string.automation_grant_summary,
                    grant.profileId == null ? "—" : grant.profileId,
                    java.text.DateFormat.getDateTimeInstance().format(
                            new java.util.Date(grant.expiresAtMillis))));
            item.setOnPreferenceClickListener(pref -> {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.automation_revoke_title)
                        .setMessage(getString(R.string.automation_revoke_confirmation,
                                grant.packageName, grant.operation))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.automation_revoke, (dialog, which) -> {
                            try {
                                grantRepository.revokeGrant(grant);
                                refreshGrants();
                            } catch (IOException error) {
                                showMessage(getString(R.string.automation_store_error));
                            }
                        })
                        .show();
                return true;
            });
            approvals.addPreference(item);
        }
    }

    private void showMessage(String message) {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
