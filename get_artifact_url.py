import sys, urllib.request, urllib.error
sys.path.insert(0, '/opt/muse/skills/custom-connector-setup/bin')
from dynamic_credentials import add_surrogate_to_request

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

url = 'https://api.github.com/repos/Sendysyah27/senzspace/actions/artifacts/14231948881/zip'
req = urllib.request.Request(url)
add_surrogate_to_request(req, 'custom.github', allowed_hosts=['api.github.com'])
opener = urllib.request.build_opener(NoRedirect)
try:
    r = opener.open(req)
    print('STATUS', r.status)
except urllib.error.HTTPError as e:
    print('HTTPError', e.code)
    loc = e.headers.get('Location')
    print('Location:', loc if loc else '(none)')
    if loc:
        with open('/home/hatch/workspace/senzspace/artifact_url.txt', 'w') as f:
            f.write(loc)
