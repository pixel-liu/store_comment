#!/usr/bin/env bash

# The legacy dump stores each data INSERT on its own line.
# Reuse its schema and load only the campus dataset that follows.
sed '/^INSERT INTO /d' /opt/foodiego/hmdp.sql | docker_process_sql
