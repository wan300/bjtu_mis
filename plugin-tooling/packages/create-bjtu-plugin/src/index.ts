#!/usr/bin/env node
import { runCli } from '@bjtu-mis/plugin-cli';

process.exitCode = await runCli(['create', ...process.argv.slice(2)]);
