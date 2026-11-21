import sys, urllib.request, urllib.error
sys.path.insert(0, '/opt/muse/skills/custom-connector-setup/bin')
from dynamic_credentials import add_surrogate_to_request

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

artifact_id = sys.argv[1]
out_file = sys.argv[2] if len(sys.argv) > 2 else '/home/hatch/workspace/senzspace/artifact_url.txt'
url = f'https://api.github.com/repos/Sendysyah27/senzspace/actions/artifacts/{artifact_id}/zip'
req = urllib.request.Request(url)
add_surrogate_to_request(req, 'custom.github', allowed_hosts=['api.github.com'])
opener = urllib.request.build_opener(NoRedirect)
try:
    r = opener.open(req)
    print('STATUS', r.status)
except urllib.error.HTTPError as e:
    print('HTTPError', e.code)
    loc = e.headers.get('Location')
    print('Location:', (loc or '(none)')[:80])
    if loc:
        with open(out_file, 'w') as f:
            f.write(loc)
