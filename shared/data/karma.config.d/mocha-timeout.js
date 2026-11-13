// Emulator tests (*Emulator*) wait for the local Firebase emulators, which can take longer than mocha's default of
// 2 seconds. The other browser tests are not affected.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 30000 });
