"""加载本地开发配置；终端已有环境变量优先，不把密钥写进源代码。"""

from pathlib import Path
from dotenv import load_dotenv


def load_local_environment():
    project = Path(__file__).resolve().parents[1]
    load_dotenv(project / ".env", override=False)
    load_dotenv(project.parent / ".env", override=False)
