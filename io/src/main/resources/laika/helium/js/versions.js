
function logError (req) {
  const status = req.status;
  const text = req.statusText;
  console.log(`[${status}]: ${text}`);
}

function populateMenu (data, localRootPrefix, currentPath, currentVersion) {
  const currentTarget = data.linkTargets.find(target => target.path === currentPath);
  const menuList = document.getElementById("version-list");
  data.versions.forEach(version => {
    const pathPrefix = localRootPrefix + "/" + version.pathSegment;
    const href = (currentTarget.versions.includes(version.pathSegment)) ? 
        pathPrefix + currentPath : pathPrefix + version.defaultLinkTarget;

    const link = document.createElement('a');
    link.innerText = version.displayValue;
    link.setAttribute("href", href);
    document.body.appendChild(a);
    
    const listItem = document.createElement("li");
    listItem.classList.add("level1");
    if (version.pathSegment === currentVersion) listItem.classList.add("active");
    listItem.appendChild(link)

    menuList.appendChild(listItem);
  });
}

function loadVersions (localRootPrefix, currentPath, currentVersion) {
  const url = localRootPrefix + "/laika/versions.json";
  const req = new XMLHttpRequest();
  req.open("GET", url);
  req.responseType = "json";
  req.onload = () => {
    if (req.status === 200) {
      populateMenu(req.response, localRootPrefix, currentPath, currentVersion);
      initMenuToggle();
    }
    else logError(req)
  };
  req.onerror = () => {
    logError(req)
  };
  req.send();
}

function initMenuToggle () {
  document.addEventListener("click", (evt) => {
    const menuClicked = evt.target.closest("#version-menu");
    const buttonClicked = evt.target.closest("#version-menu-toggle");
    if (!menuClicked && !buttonClicked) {
      document.getElementById("version-menu").classList.remove("versions-open")
    }
  });
  document.getElementById("version-menu-toggle").onclick = () => {
    document.getElementById("version-menu").classList.add("versions-open");
  };
}

function initVersions (localRootPrefix, currentPath, currentVersion) {
  document.addEventListener('DOMContentLoaded', () => {
    loadVersions(localRootPrefix, currentPath, currentVersion);
  });
}