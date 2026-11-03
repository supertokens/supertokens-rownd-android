import unittest
from email_e2e_diagnostics import checkpoint_markers


class CheckpointMarkersTest(unittest.TestCase):
    def test_keeps_allowed_fields_and_discards_secrets(self):
        output = 'Authorization: Bearer secret\nEMAIL_E2E_DIAGNOSTIC {"currentPid":7,"token":"secret","dom":{"value":"empty","count":1,"origin":"http://127.0.0.1:8848","path":"/secret?token=x","other":"secret"}}\n'
        result = checkpoint_markers(output)
        self.assertEqual(result, [{'currentPid': 7, 'dom': {'value': 'empty', 'count': 1, 'origin': 'http://127.0.0.1:8848'}}])
        self.assertNotIn('secret', str(result))

    def test_ignores_invalid_marker(self):
        self.assertEqual(checkpoint_markers('EMAIL_E2E_DIAGNOSTIC {bad}\n'), [])

    def test_phase_and_history_are_allowlisted(self):
        output = 'INSTRUMENTATION_STATUS: rownd_e2e=EMAIL_E2E_DIAGNOSTIC {"phase":"rendered","history":[{"value":"empty"},{"value":"secret-token","callback":"parsed"}]}\n'
        self.assertEqual(checkpoint_markers(output), [{'phase': 'rendered', 'history': [{'value': 'empty'}, {'callback': 'parsed'}]}])


if __name__ == '__main__':
    unittest.main()
