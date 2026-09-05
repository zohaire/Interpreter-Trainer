import unittest
from check_app_configuration import REQUIRED, problems

class DistributionConfigurationTest(unittest.TestCase):
    def test_empty_build_cannot_be_distributed(self):
        self.assertEqual(len(problems({})), len(REQUIRED))

    def test_populated_configuration_does_not_allow_insecure_endpoint(self):
        env = dict.fromkeys(REQUIRED, 'test-only-public-identifier')
        for endpoint in ('http://backend.example', 'https://user:password@backend.example', 'https://backend.example/?token=example'):
            env['INTERPRETER_BACKEND_URL'] = endpoint
            self.assertTrue(problems(env))

    def test_presence_check_accepts_complete_configuration_without_claiming_live_verification(self):
        env = dict.fromkeys(REQUIRED, 'test-only-public-identifier')
        env['INTERPRETER_BACKEND_URL'] = 'https://backend.example'
        self.assertEqual(problems(env), [])

if __name__ == '__main__':
    unittest.main()
