import unittest

from verify_offline_journey import offline_device_state


class OfflineDeviceStateTest(unittest.TestCase):
    def test_airplane_and_wifi_off_with_no_default_network(self):
        # An emulator can retain mobile_data=1 while airplane mode has disconnected it.
        self.assertTrue(offline_device_state("1", "0", "Active default network: none\n  mDefaultNetwork=null\n"))

    def test_active_network_or_radio_control_failure_never_counts_as_offline(self):
        self.assertFalse(offline_device_state("1", "0", "Active default network: 107\n  mDefaultNetwork=107\n"))
        self.assertFalse(offline_device_state("0", "0", "Active default network: none\n"))
        self.assertFalse(offline_device_state("1", "1", "Active default network: none\n"))


if __name__ == "__main__":
    unittest.main()
