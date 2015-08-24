# GitHub Pull Request Comments Plugin

This Jenkins plugin builds pull requests from GitHub and will report the results directly to the pull request via
the [GitHub Commit Status API](http://developer.github.com/v3/repos/statuses/)

Аdding a new pull request or new commit to an existing pull request will start a new
build.

For more details, see https://wiki.jenkins-ci.org/display/JENKINS/GitHub+pull+request+builder+plugin

### Required Jenkins Plugins:
* github-api plugin (https://wiki.jenkins-ci.org/display/JENKINS/GitHub+API+Plugin)
* github plugin (https://wiki.jenkins-ci.org/display/JENKINS/GitHub+Plugin)
* git plugin (https://wiki.jenkins-ci.org/display/JENKINS/Git+Plugin)
* credentials plugin (https://wiki.jenkins-ci.org/display/JENKINS/Credentials+Plugin)
* plain credentials plugin (https://wiki.jenkins-ci.org/display/JENKINS/Plain+Credentials+Plugin)

### Pre-installation:
* I recommend to create GitHub 'bot' user that will be used for communication with GitHub (however you can use your own account if you want).
* The user needs to have push rights for your repository (must be collaborator (user repo) or must have Push & Pull rights (organization repo)).  
* If you want to use GitHub hooks have them set automatically the user needs to have administrator rights for your repository (must be owner (user repo) or must have Push, Pull & Administrative rights (organization repo))  

### Installation:
* Install the plugin.  
* Go to ``Manage Jenkins`` -> ``Configure System`` -> ``GitHub pull requests comments`` section.

* Add GitHub usernames of admins (these usernames will be used as defaults in new jobs).  
* Under Advanced, you can modify:  
  * The crontab line. This specify default setting for new jobs.
* Under Application Setup
  * There are global and job default extensions that can be configured for things like:
    * Commit status updates
    * Adding lines from the build log to the build result message
    * etc.
* Save to preserve your changes.  

### Credentials
* If you are using Enterprise GitHub set the server api URL in ``GitHub server api URL``. Otherwise leave there ``https://api.github.com``.
* A GitHub API token or username password can be used for access to the GitHub API
* To setup credentials for a given GitHub Server API URL:
  * Click Add next to the ``Credentials`` drop down
    * For a token select ``Kind`` -> ``Secret text``
      * If you haven't generated an access token you can generate one in ``Test Credentials...``.  
        * Set your 'bot' user's GitHub username and password.  
        * Press the ``Create Access Token`` button  
        * Jenkins will create a token credential, and give you the id of the newly created credentials.  The default description is: ``serverAPIUrl + " GitHub auto generated token credentials"``.
    * For username/password us ``Kind`` -> ``Username with password``
      * The scope determines what has access to the credentials you are about to create
    * The first part of the description is used to show different credentials in the drop down, so use something semi-descriptive
    * Click ``Add``
  * Credentials will automatically be created in the domain given by the ``GitHub Server API URL`` field.
  * Select the credentials you just created in the drop down.
  * The first fifty characters in the Description are used to differentiate credentials per job, so again use something semi-descriptive
* Add as many GitHub auth sections as you need, even duplicate server URLs


### Creating a job:
* Create a new job.  
* Add the project's GitHub URL to the ``GitHub project`` field (the one you can enter into browser. eg: ``https://github.com/janinko/ghprb``)  
* Select Git SCM.  
* Add your GitHub ``Repository URL``.  
* Under Advanced, set ``Name`` to ``origin`` and:
  * If you **just** want to build PRs, set ``refspec`` to ``+refs/pull/*:refs/remotes/origin/pr/*``
  * If you want to build PRs **and** branches, set ``refspec`` to ``+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*`` (see note below about [parameterized builds](#parameterized-builds))
* In ``Branch Specifier``, enter ``${sha1}`` instead of the default ``*/master``.
* If you want to use the actual commit in the pull request, use ``${ghprbActualCommit}`` instead of ``${sha1}``
* Under ``Build Triggers``, check ``GitHub pull requests builder``.
  * Add admins for this specific job.  
  * If you want to use GitHub hooks for automatic testing, read the help for ``Use github hooks for build triggering`` in job configuration. Then you can check the checkbox.
  * In Advanced, you can modify:  
    * The crontab line for this specific job. This schedules polling to GitHub for new changes in Pull Requests.  

Make sure you **DON'T** have ``Prune remote branches before build`` advanced option selected, since it will prune the branch created to test this build.  

#### Parameterized Builds
If you want to manually build the job, in the job setting check ``This build is parameterized`` and add string parameter named ``sha1`` with a default value of ``master``. When starting build give the ``sha1`` parameter commit id you want to build or refname (eg: ``origin/pr/9/head``).
