if __name__ == '__main__':
    # def convert_dtype(col):
    #     if pd.api.types.is_object_dtype(col.dtype):
    #         # print(col.name)
    #         return 'dict' if isinstance(col.iloc[0], dict) else 'list'
    #     else:
    #         return col.dtype.name
    #
    # import io
    # import json
    # import pandas as pd
    #
    # # 将JSON字符串转换为二进制流对象
    # json_str = '{"a": 1, "c":  ["b", 11], "aa": {"key": 1}}'
    # json_bytes = json_str.encode('utf-8')
    # json_io = io.BytesIO(json_bytes)
    #
    # # 使用pandas读取JSON数据
    # df = pd.read_json(json_io, lines=True)
    # print(df)
    #
    # data = {}
    # # 查看每列的数据类型
    # for column in df.columns:
    #     data[column] = convert_dtype(df[column])
    #
    # print(data)

    import boto3
    from smart_open import open
    # import smart_open
    import pandas as pd
    import json

    aws_access_key_id = 'AKIATXCDS4PBOK26MY7P'
    aws_secret_access_key = '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'
    bucket = 'fp-meidong'
    key_path = 'mch_test4/test_pandas.json'

    session = boto3.Session(
        region_name='us-east-1',
        aws_access_key_id=aws_access_key_id,
        aws_secret_access_key=aws_secret_access_key)
    url = f's3://{bucket}/{key_path}'
    print(url)
    json_str = ''
    with open(url, transport_params={'client': session.client('s3')}) as fout:
        # df = fout.read(32)
        for line in fout:
            json_str += line.strip()
        # df = pd.read_json(fout)
        # print(df)
        # for line in fout:
        #     print(line)
    print(json_str)
    print(pd.read_json(json_str, lines=True))
