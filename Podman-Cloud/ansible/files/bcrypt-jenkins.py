#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Cloudsdoers
"""Genera un hash bcrypt en formato $2a$ compatible con Jenkins JCasC."""
import sys
import bcrypt

password = sys.argv[1].encode()
rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 10
h = bcrypt.hashpw(password, bcrypt.gensalt(rounds=rounds)).decode()
# Jenkins espera prefijo $2a$ (Python genera $2b$)
h = h.replace("$2b$", "$2a$")
print(h)
