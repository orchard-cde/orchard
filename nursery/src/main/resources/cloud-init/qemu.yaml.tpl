#cloud-config
users:
  - name: cultivator
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
${ssh_authorized_keys_block}packages:
  - docker.io
  - git
  - curl
runcmd:
  - curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  - apt-get install -y nodejs
  - npm install -g @devcontainers/cli@${cli_version}
  - systemctl enable docker
  - systemctl start docker
  - usermod -aG docker cultivator
  - mkdir -p /workspace
  - chown cultivator:cultivator /workspace
