"""
静态检查：找出「声明了却从未赋值」的 UI 组件字段。

这类字段在 build*Card 里被 add() 进容器时会抛
NullPointerException: Cannot read field "parent" because "<parameter1>" is null。
javac 不会报错 —— 编译通过、运行即崩。

真实案例：joinError 只声明未赋值，导致 v2.3.0 启动即崩，
而当时 2116 项逻辑自检全部通过（因为自检从未构造过界面）。

用法：python check-null-fields.py <AppFrame.java>
退出码 0 = 通过，1 = 发现未赋值字段。
"""
import re
import sys

UI_TYPES = (
    'JLabel', 'JTextField', 'JTextArea', 'JButton', 'JPanel', 'JComponent',
    'Card', 'Btn', 'JCheckBox', 'JComboBox', 'JList', 'JScrollPane',
    'JProgressBar', 'JSlider', 'JSpinner', 'JRadioButton', 'JSeparator',
    'CardLayout', 'Box', 'BorderFactory',
)

if len(sys.argv) < 2:
    print('用法: python check-null-fields.py <java 文件>')
    sys.exit(2)

PATH = sys.argv[1]
src = open(PATH, encoding='utf-8').read()
lines = src.split('\n')

decl_re = re.compile(
    r'^\s*private\s+(?:static\s+)?(?:final\s+)?'
    r'(' + '|'.join(UI_TYPES) + r')\s+(\w+)\s*(=[^;]*)?;\s*$'
)

fields = []
for i, l in enumerate(lines, 1):
    m = decl_re.match(l)
    if m:
        fields.append((m.group(1), m.group(2), i, (m.group(3) or '').strip()))

print('== UI 字段声明 (%d 个) ==' % len(fields))
missing = []
for t, n, ln, init in fields:
    has_init = bool(init) and init != 'null'
    assigned = has_init
    for i, l in enumerate(lines, 1):
        if i == ln:
            continue
        # 赋值： name =  但排除 ==  !=  <=  >=  +=  -=  *=
        if re.search(r'(?<![\w.])' + re.escape(n) + r'\s*=(?!=)', l):
            assigned = True
            break
    flag = 'OK  ' if assigned else 'MISS'
    if not assigned:
        missing.append((t, n, ln))
    print('  %s L%-5d %-14s %s%s' % (flag, ln, t, n, ('  = ' + init) if init else ''))

print()
if missing:
    print('!! 从未赋值的字段 (%d 个) —— 必然导致运行时空指针：' % len(missing))
    for t, n, ln in missing:
        print('  L%-5d %-14s %s' % (ln, t, n))
    sys.exit(1)
else:
    print('OK：所有 UI 字段均已赋值。')
    sys.exit(0)
