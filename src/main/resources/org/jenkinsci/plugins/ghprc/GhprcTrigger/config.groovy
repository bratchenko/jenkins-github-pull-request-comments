j = namespace("jelly:core")
f = namespace("/lib/form")

f.entry(field: "gitHubAuthId", title:_("GitHub API credentials")) {
  f.select()
}

f.entry(field: "useGitHubHooks", title: "Use github hooks for build triggering") {
  f.checkbox(default: true)
}
f.advanced() {
  f.entry(field: "autoCloseFailedPullRequests", title: _("Close failed pull request automatically?")) {
    f.checkbox(default: descriptor.autoCloseFailedPullRequests) 
  }
  f.entry(field: "displayBuildErrorsOnDownstreamBuilds", title: _("Display build errors on downstream builds?")) {
    f.checkbox(default: descriptor.displayBuildErrorsOnDownstreamBuilds) 
  }
  f.entry(field: "cron", title: _("Crontab line"), help: "/descriptor/hudson.triggers.TimerTrigger/help/spec") {
    f.textbox(default: descriptor.cron, checkUrl: "'descriptorByName/hudson.triggers.TimerTrigger/checkSpec?value=' + encodeURIComponent(this.value)") 
  }
  f.entry(field: "buildDescTemplate", title: _("Build description template")) {
      f.textarea()
  }
}
f.advanced(title: _("Trigger Setup")) {
  f.entry(title: _("Trigger Setup")) {
    f.hetero_list(items: instance == null ? null : instance.extensions, 
        name: "extensions", oneEach: "true", hasHeader: "true", descriptors: descriptor.getExtensionDescriptors()) 
  }
}
