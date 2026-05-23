# data/

所有由 jcgraph 生成的索引库统一放在这里,保持项目根目录干净。

## 约定

运行 `index` 时一律用 `--db data/<名字>.db`。`.db` 和它的缓存目录
`<名字>.db.work/`(默认 `<db>.work`)都会落在本目录内。

```bash
# 普通输入(英文路径)
java -jar target/jcgraph.jar index path/to/app.jar --db data/app.db
java -jar target/jcgraph.jar search OSSClient        --db data/app.db
```

## 中文目录输入(案例/、参考/)

Java 8 在 Windows(GBK)下,中文路径作为命令行参数会解码失败,导致
"collected 0 classes"。需先 `cd` 进该目录,再用纯 ASCII 的子路径:

```bash
cd 案例
java -jar ../target/jcgraph.jar index frlib --db ../data/case.db --no-materialize
java -jar ../target/jcgraph.jar search OSSClient --db ../data/case.db
```

## 备注

- 本目录下的 `*.db` / `*.db.work/` 已被 `.gitignore` 忽略,不进版本库。
- `.db` 内存的是 class 文件的**绝对路径**,所以**不要手动移动** `.db.work/`;
  需要换位置时直接重新 `index`。
- 大库慎用全量物化(去掉 `--no-materialize`):会反编译每个 class,极慢且占数十 GB。
