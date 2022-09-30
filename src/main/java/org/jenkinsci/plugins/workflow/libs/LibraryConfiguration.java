/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.libs;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Collection;

/**
 * User configuration for one library.
 */
public class LibraryConfiguration extends AbstractDescribableImpl<LibraryConfiguration> {

    private final String name;
    private final LibraryRetriever retriever;
    private String defaultVersion;
    private boolean implicit;
    private boolean allowVersionOverride = true;
    private boolean allowBRANCH_NAME = false;
    private boolean allowBRANCH_NAME_PR = false;
    // Print defaultedVersion() progress resolving BRANCH_NAME
    // to a build console log. This is exposed as UI checkbox
    // for deployment troubleshooting, but is primarily intended
    // for programmatic consumption e.g. in unit-tests.
    private boolean traceBRANCH_NAME = false;
    private boolean includeInChangesets = true;
    private LibraryCachingConfiguration cachingConfiguration = null;

    @DataBoundConstructor public LibraryConfiguration(String name, LibraryRetriever retriever) {
        this.name = Util.fixEmptyAndTrim(name);
        this.retriever = retriever;
    }

    /**
     * Library name.
     * Should match {@link Library#value}, up to the first occurrence of {@code @}, if any.
     */
    public String getName() {
        return name;
    }

    public LibraryRetriever getRetriever() {
        return retriever;
    }

    /**
     * The default version to use with {@link LibraryRetriever#retrieve} if none other is specified.
     */
    public String getDefaultVersion() {
        return defaultVersion;
    }

    @DataBoundSetter public void setDefaultVersion(String defaultVersion) {
        this.defaultVersion = Util.fixEmptyAndTrim(defaultVersion);
    }

    /**
     * Whether the library should be made accessible to qualifying jobs
     * without any explicit {@link Library} declaration.
     */
    public boolean isImplicit() {
        return implicit;
    }

    @DataBoundSetter public void setImplicit(boolean implicit) {
        this.implicit = implicit;
    }

    /**
     * Whether jobs should be permitted to override {@link #getDefaultVersion}.
     */
    public boolean isAllowVersionOverride() {
        return allowVersionOverride;
    }

    @DataBoundSetter public void setAllowVersionOverride(boolean allowVersionOverride) {
        this.allowVersionOverride = allowVersionOverride;
    }

    /**
     * Whether jobs should be permitted to specify literally @Library('libname@${BRANCH_NAME}')
     * in pipeline files (from SCM) to try using the same branch name of library as of the job
     * definition. If such branch name does not exist, fall back to retrieve() defaultVersion.
     */

    public boolean isAllowBRANCH_NAME() {
        return allowBRANCH_NAME;
    }

    @DataBoundSetter public void setAllowBRANCH_NAME(boolean allowBRANCH_NAME) {
        this.allowBRANCH_NAME = allowBRANCH_NAME;
    }

    public boolean isTraceBRANCH_NAME() {
        return traceBRANCH_NAME;
    }

    @DataBoundSetter public void setTraceBRANCH_NAME(boolean traceBRANCH_NAME) {
        this.traceBRANCH_NAME = traceBRANCH_NAME;
    }

    public boolean isAllowBRANCH_NAME_PR() {
        return allowBRANCH_NAME_PR;
    }

    @DataBoundSetter public void setAllowBRANCH_NAME_PR(boolean allowBRANCH_NAME_PR) {
        this.allowBRANCH_NAME_PR = allowBRANCH_NAME_PR;
    }

    /**
     * Whether to include library changes in reported changes in a job {@link #getIncludeInChangesets}.
     */
    public boolean getIncludeInChangesets() {
        return includeInChangesets;
    }

    @DataBoundSetter public void setIncludeInChangesets(boolean includeInChangesets) {
        this.includeInChangesets = includeInChangesets;
    }

    public LibraryCachingConfiguration getCachingConfiguration() {
        return cachingConfiguration;
    }

    @DataBoundSetter public void setCachingConfiguration(LibraryCachingConfiguration cachingConfiguration) {
        this.cachingConfiguration = cachingConfiguration;
    }

    @NonNull boolean defaultedChangelogs(@CheckForNull Boolean changelog) throws AbortException {
      if (changelog == null) {
        return includeInChangesets;
      } else {
        return changelog;
      }
    }

    @NonNull String defaultedVersion(@CheckForNull String version) throws AbortException {
        return defaultedVersion(version, null, null);
    }

    @NonNull String defaultedVersion(@CheckForNull String version, Run<?, ?> run, TaskListener listener) throws AbortException {
        PrintStream logger = null;
        if (traceBRANCH_NAME && listener != null) {
            logger = listener.getLogger();
        }
        if (logger != null) {
            logger.println("defaultedVersion(): Resolving '" + version + "'");
        }

        if (version == null) {
            if (defaultVersion == null) {
                throw new AbortException("No version specified for library " + name);
            } else {
                return defaultVersion;
            }
        } else if (allowVersionOverride && !"${BRANCH_NAME}".equals(version)) {
            return version;
        } else if (allowBRANCH_NAME && "${BRANCH_NAME}".equals(version)) {
            String runVersion = null;
            Item runParent = null;
            if (run != null && listener != null) {
                try {
                    runParent = run.getParent();
                } catch (Exception x) {
                    // no-op, keep null
                }
            }

            if (logger != null) {
                logger.println("defaultedVersion(): Resolving BRANCH_NAME; " +
                    (runParent == null ? "without" : "have") +
                    " a runParent object");
            }

            // without a runParent we can't validateVersion() anyway
            if (runParent != null) {
                // First, check if envvar BRANCH_NAME is defined?
                // Trust the plugins and situations where it is set.
                try {
                    runVersion = run.getEnvironment(listener).get("BRANCH_NAME", null);
                    if (logger != null) {
                        if (runVersion != null) {
                            logger.println("defaultedVersion(): Resolved envvar BRANCH_NAME='" + runVersion + "'");
                        } else {
                            logger.println("defaultedVersion(): Did not resolve envvar BRANCH_NAME: not in env");
                        }
                    }
                } catch (Exception x) {
                    runVersion = null;
                    if (logger != null) {
                        logger.println("defaultedVersion(): Did not resolve envvar BRANCH_NAME: " + x.getMessage());
                    }
                }

                if (runVersion == null) {
                    // Probably not in a multibranch pipeline workflow
                    // type of job?
                    // Ask for SCM source of the pipeline (if any),
                    // as the most authoritative source of the branch
                    // name we want:
                    SCM scm0 = null;

                    if (runParent instanceof WorkflowJob) {
                        // This covers both "Multibranch Pipeline"
                        // and "Pipeline script from SCM" jobs;
                        // it also covers "inline" pipeline scripts
                        // but should return an empty Collection of
                        // SCMs since there is no SCM attached to
                        // the "static source".
                        // TODO: If there are "pre-loaded libraries"
                        // in a Jenkins deployment, can they interfere?
                        if (logger != null) {
                            logger.println("defaultedVersion(): inspecting WorkflowJob for a FlowDefinition");
                        }
                        FlowDefinition fd = ((WorkflowJob)runParent).getDefinition();
                        if (fd != null) {
                            Collection<SCM> fdscms = (Collection<SCM>) fd.getSCMs();
                            if (fdscms.isEmpty()) {
                                if (logger != null) {
                                    logger.println("defaultedVersion(): FlowDefinition '" +
                                        fd.getClass().getName() +
                                        "' is not associated with any SCMs");
                                }
                            } else {
                                if (logger != null) {
                                    logger.println("defaultedVersion(): inspecting FlowDefinition '" +
                                        fd.getClass().getName() +
                                        "' for SCMs it might use");
                                }
                                for (SCM scmN : fdscms) {
                                    if (logger != null) {
                                        logger.println("defaultedVersion(): inspecting SCM '" +
                                            scmN.getClass().getName() +
                                            "': " + scmN.toString());
                                    }
                                    if ("hudson.plugins.git.GitSCM".equals(scmN.getClass().getName())) {
                                        // The best we can do here is accept
                                        // the first seen SCM (with branch
                                        // support which we know how to query).
                                        scm0 = scmN;
                                        break;
                                    }
                                }
                            }
                        }
                    }

/*
                    // NOTE: the list of SCMs used by the run does not
                    // seem trustworthy: if an "inline" pipeline script
                    // (not from SCM) is used, there is no "relevant"
                    // branch name to request; however the list of SCMs
                    // would be populated as @Library lines are processed
                    // and some SCM sources get checked out.
                    if (scm0 == null && run instanceof WorkflowRun) {
                        // This covers both "Multibranch Pipeline"
                        // and "Pipeline script from SCM" jobs;
                        // it also covers "inline" pipeline scripts
                        // but throws a hudson.AbortException since
                        // there is no SCM attached.
                        if (logger != null) {
                            logger.println("defaultedVersion(): inspecting WorkflowRun");
                        }
                        try {
                            WorkflowRun wfRun = (WorkflowRun) run;
                            scm0 = wfRun.getSCMs().get(0);
                        } catch (Exception x) {
                            if (logger != null) {
                                logger.println("defaultedVersion(): " +
                                    "Did not get first listed SCM: " +
                                    x.getMessage());
                            }
                        }
                    }
*/

                    if (scm0 != null) {
                        // Avoid importing GitSCM and so requiring that
                        // it is always installed even if not used by
                        // particular Jenkins deployment (using e.g.
                        // SVN, Gerritt, etc.). Our aim is to query this:
                        //   runVersion = scm0.getBranches().first().getExpandedName(run.getEnvironment(listener));
                        // https://mkyong.com/java/how-to-use-reflection-to-call-java-method-at-runtime/
                        if (logger != null) {
                            logger.println("defaultedVersion(): " +
                                "inspecting first listed SCM: " +
                                scm0.toString());
                        }

                        Class noparams[] = {};
                        Class[] paramEnvVars = new Class[1];
                        paramEnvVars[0] = EnvVars.class;
                        if ("hudson.plugins.git.GitSCM".equals(scm0.getClass().getName())) {
                            // https://javadoc.jenkins.io/plugin/git/hudson/plugins/git/GitSCM.html#getBranches() =>
                            // https://javadoc.jenkins.io/plugin/git/hudson/plugins/git/BranchSpec.html#toString()
                            Method methodGetBranches = null;
                            try {
                                methodGetBranches = scm0.getClass().getDeclaredMethod("getBranches", noparams);
                            } catch (Exception x) {
                                // NoSuchMethodException | SecurityException | NullPointerException
                                methodGetBranches = null;
                            }
                            if (methodGetBranches != null) {
                                Object branchList = null;
                                try {
                                    branchList = methodGetBranches.invoke(scm0);
                                } catch (Exception x) {
                                    // InvocationTargetException | IllegalAccessException
                                    branchList = null;
                                }
                                if (branchList != null && branchList instanceof List) {
                                    Object branch0 = ((List<Object>) branchList).get(0);
                                    if (branch0 != null && "hudson.plugins.git.BranchSpec".equals(branch0.getClass().getName())) {
                                        Method methodGetExpandedName = null;
                                        try {
                                            methodGetExpandedName = branch0.getClass().getDeclaredMethod("getExpandedName", paramEnvVars);
                                        } catch (Exception x) {
                                            methodGetExpandedName = null;
                                        }
                                        if (methodGetExpandedName != null) {
                                            // Handle possible shell-templated branch specs:
                                            Object expandedBranchName = null;
                                            try {
                                                expandedBranchName = methodGetExpandedName.invoke(branch0, run.getEnvironment(listener));
                                            } catch (Exception x) {
                                                // IllegalAccessException | IOException
                                                expandedBranchName = null;
                                            }
                                            if (expandedBranchName != null) {
                                                runVersion = expandedBranchName.toString();
                                            }
                                        } else {
                                            if (logger != null) {
                                                logger.println("defaultedVersion(): " +
                                                    "did not find method BranchSpec.getExpandedName()");
                                            }
                                        }
                                        if (runVersion == null || "".equals(runVersion)) {
                                            runVersion = branch0.toString();
                                        }
                                    } else {
                                        // unknown branchspec class, make no blind guesses
                                        if (logger != null) {
                                            logger.println("defaultedVersion(): " +
                                                "list of branches did not return a " +
                                                "BranchSpec class instance, but " +
                                                (branch0 == null ? "null" :
                                                branch0.getClass().getName()));
                                        }
                                    }
                                } else {
                                    if (logger != null) {
                                        logger.println("defaultedVersion(): " +
                                            "getBranches() did not return a " +
                                            "list of branches: " +
                                            (branchList == null ? "null" :
                                            branchList.getClass().getName()));
                                    }
                                }
                            } else {
                                // not really the GitSCM we know?
                                if (logger != null) {
                                    logger.println("defaultedVersion(): " +
                                        "did not find method GitSCM.getBranches()");
                                }
                            }
                        } else {
                            // else SVN, Gerritt or some other SCM -
                            // add handling when needed and known how
                            // or rely on BRANCH_NAME (if set) below...
                            if (logger != null) {
                                logger.println("defaultedVersion(): " +
                                    "the first listed SCM was not of currently " +
                                    "supported class with recognized branch support: " +
                                    scm0.getClass().getName());
                            }
                        }

                        // Still alive? Chop off leading '*/'
                        // (if any) from single-branch MBP and
                        // plain "Pipeline" job definitions.
                        if (runVersion != null) {
                            runVersion = runVersion.replaceFirst("^\\*/", "");
                            if (logger != null) {
                                logger.println("defaultedVersion(): " +
                                    "Discovered runVersion '" + runVersion +
                                    "' in SCM source of the pipeline");
                            }
                        }
                    }
                }

                // Note: if runVersion remains null (unresolved -
                // with other job types and/or SCMs maybe setting
                // other envvar names), we might drill into names
                // like GIT_BRANCH, GERRIT_BRANCH etc. but it would
                // not be too scalable. So gotta stop somewhere.
                // We would however look into (MBP-defined for PRs)
                // CHANGE_BRANCH and CHANGE_TARGET as other fallbacks
                // below.
            } else {
                if (logger != null) {
                    logger.println("defaultedVersion(): Trying to default: " +
                        "without a runParent we can't validateVersion() anyway");
                }
            }

            if (runParent == null || runVersion == null || "".equals(runVersion)) {
                // Current build does not know a BRANCH_NAME envvar,
                // or it's an empty string, or this request has null
                // args for run/listener needed for validateVersion()
                // below, or some other problem occurred.
                // Fall back if we can:
                if (logger != null) {
                    logger.println("defaultedVersion(): Trying to default: " +
                        "runVersion is " +
                        (runVersion == null ? "null" :
                            ("".equals(runVersion) ? "empty" : runVersion)));
                }
                if (defaultVersion == null) {
                    throw new AbortException("No version specified for library " + name);
                } else {
                    return defaultVersion;
                }
            }

            // Check if runVersion is resolvable by LibraryRetriever
            // implementation (SCM, HTTP, etc.); fall back if not:
            if (retriever != null) {
                if (logger != null) {
                    logger.println("defaultedVersion(): Trying to validate runVersion: " + runVersion);
                }

                FormValidation fv = retriever.validateVersion(name, runVersion, runParent);

                if (fv != null && fv.kind == FormValidation.Kind.OK) {
                    return runVersion;
                }

                if (runVersion.startsWith("PR-") && allowBRANCH_NAME_PR) {
                    // MultiBranch Pipeline support for pull requests
                    // sets BRANCH_NAME="PR-123" and keeps source
                    // and target branch names in CHANGE_BRANCH and
                    // CHANGE_TARGET respectively.

                    // First check for possible PR-source branch of
                    // pipeline coordinated with a PR of trusted
                    // shared library (if branch exists in library,
                    // after repo protections involved, it is already
                    // somewhat trustworthy):
                    try {
                        runVersion = run.getEnvironment(listener).get("CHANGE_BRANCH", null);
                    } catch (Exception x) {
                        runVersion = null;
                    }
                    if (runVersion != null && !("".equals(runVersion))) {
                        if (logger != null) {
                            logger.println("defaultedVersion(): Trying to validate CHANGE_BRANCH: " + runVersion);
                        }
                        fv = retriever.validateVersion(name, runVersion, runParent);

                        if (fv != null && fv.kind == FormValidation.Kind.OK) {
                            return runVersion;
                        }
                    }

                    // Next check for possible PR-target branch of
                    // pipeline coordinated with existing version of
                    // trusted shared library:
                    try {
                        runVersion = run.getEnvironment(listener).get("CHANGE_TARGET", null);
                    } catch (Exception x) {
                        runVersion = null;
                    }
                    if (runVersion != null && !("".equals(runVersion))) {
                        if (logger != null) {
                            logger.println("defaultedVersion(): Trying to validate CHANGE_TARGET: " + runVersion);
                        }
                        fv = retriever.validateVersion(name, runVersion, runParent);

                        if (fv != null && fv.kind == FormValidation.Kind.OK) {
                            return runVersion;
                        }
                    }
                } // else not a PR or not allowBRANCH_NAME_PR
            }

            // No retriever, or its validateVersion() did not confirm
            // usability of BRANCH_NAME string value as the version...
            if (logger != null) {
                logger.println("defaultedVersion(): Trying to default: " +
                    "could not resolve runVersion which is " +
                    (runVersion == null ? "null" :
                        ("".equals(runVersion) ? "empty" : runVersion)));
            }
            if (defaultVersion == null) {
                throw new AbortException("BRANCH_NAME version " + runVersion +
                    " was not found, and no default version specified, for library " + name);
            } else {
                return defaultVersion;
            }
        } else {
            throw new AbortException("Version override not permitted for library " + name);
        }
    }

    @Extension public static class DescriptorImpl extends Descriptor<LibraryConfiguration> {

        // TODO JENKINS-20020 ought to be unnecessary
        @Restricted(NoExternalUse.class) // Jelly
        public Collection<LibraryRetrieverDescriptor> getRetrieverDescriptors() {
            StaplerRequest req = Stapler.getCurrentRequest();
            Item it = req != null ? req.findAncestorObject(Item.class) : null;
            return DescriptorVisibilityFilter.apply(it != null ? it : Jenkins.get(), ExtensionList.lookup(LibraryRetrieverDescriptor.class));
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (name.isEmpty()) {
                return FormValidation.error("You must enter a name.");
            }
            // Currently no character restrictions.
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckDefaultVersion(@AncestorInPath Item context, @QueryParameter String defaultVersion, @QueryParameter boolean implicit, @QueryParameter boolean allowVersionOverride, @QueryParameter boolean allowBRANCH_NAME, @QueryParameter boolean allowBRANCH_NAME_PR, @QueryParameter String name) {
            if (defaultVersion.isEmpty()) {
                if (implicit) {
                    return FormValidation.error("If you load a library implicitly, you must specify a default version.");
                }
                if (allowBRANCH_NAME) {
                    return FormValidation.error("If you allow use of literal '@${BRANCH_NAME}' for overriding a default version, you must define that version as fallback.");
                }
                if (!allowVersionOverride) {
                    return FormValidation.error("If you deny overriding a default version, you must define that version.");
                }
                if (allowBRANCH_NAME_PR) {
                    return FormValidation.warning("This setting has no effect when you do not allow use of literal '@${BRANCH_NAME}' for overriding a default version");
                }
                return FormValidation.ok();
            } else {
                if ("${BRANCH_NAME}".equals(defaultVersion)) {
                    if (!allowBRANCH_NAME) {
                        return FormValidation.error("Use of literal '@${BRANCH_NAME}' not allowed in this configuration.");
                    }

                    // The context is not a particular Run (might be a Job)
                    // so we can't detect which BRANCH_NAME is relevant:
                    String msg = "Cannot validate default version: " +
                            "literal '@${BRANCH_NAME}' is reserved " +
                            "for pipeline files from SCM";
                    if (implicit) {
                        // Someone might want to bind feature branches of
                        // job definitions and implicit libs by default?..
                        return FormValidation.warning(msg);
                    } else {
                        return FormValidation.error(msg);
                    }
                }

                for (LibraryResolver resolver : ExtensionList.lookup(LibraryResolver.class)) {
                    for (LibraryConfiguration config : resolver.fromConfiguration(Stapler.getCurrentRequest())) {
                        if (config.getName().equals(name)) {
                            return config.getRetriever().validateVersion(name, defaultVersion, context);
                        }
                    }
                }
                return FormValidation.ok("Cannot validate default version until after saving and reconfiguring.");
            }
        }

        /* TODO currently impossible; autoCompleteField does not support passing neighboring fields:
        public AutoCompletionCandidates doAutoCompleteDefaultVersion(@AncestorInPath Item context, @QueryParameter String defaultVersion, @QueryParameter String name) {
            AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            for (LibraryResolver resolver : ExtensionList.lookup(LibraryResolver.class)) {
                for LibraryConfiguration config : resolver.fromConfiguration(Stapler.getCurrentRequest()) {
                    // TODO define LibraryRetriever.completeVersions
                    if (config.getName().equals(name) && config.getRetriever() instanceof SCMSourceRetriever) {
                        try {
                            for (String revision : ((SCMSourceRetriever) config.getRetriever()).getScm().fetchRevisions(null, context)) {
                                if (revision.startsWith(defaultVersion)) {
                                    candidates.add(revision);
                                }
                            }
                        } catch (IOException | InterruptedException x) {
                            LOGGER.log(Level.FINE, null, x);
                        }
                    }
                }
            }
            return candidates;
        }
        */

    }

}
