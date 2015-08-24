j = namespace("jelly:core")
f = namespace("/lib/form")


f.section(title: descriptor.displayName) {
  f.entry(field: "githubAuth", title: _("GitHub Auth")) {
    f.repeatableProperty(field: "githubAuth", default: descriptor.getGithubAuth()) 
  }
  f.entry(field: "outputFile", title: _("Output file name")) {
    f.textbox(default: "output.txt")
  }
  f.advanced() {
    f.entry(field: "unstableAs", title: _("Mark Unstable build in github as")) {
      f.select() 
    }
    f.entry(field: "autoCloseFailedPullRequests", title: _("Close failed pull request automatically?")) {
      f.checkbox() 
    }
    f.entry(field: "displayBuildErrorsOnDownstreamBuilds", title: _("Display build errors on downstream builds?")) {
      f.checkbox() 
    }
    f.entry(field: "cron", title: _("Crontab line"), help: "/descriptor/hudson.triggers.TimerTrigger/help/spec") {
      f.textbox(default: "H/5 * * * *", checkUrl: "'descriptorByName/hudson.triggers.TimerTrigger/checkSpec?value=' + encodeURIComponent(this.value)") 
    }
  }
  f.entry(title: _("Application Setup")) {
    f.hetero_list(items: descriptor.extensions, name: "extensions", oneEach: "true", hasHeader: "true", descriptors: descriptor.getGlobalExtensionDescriptors()) 
  }
}
