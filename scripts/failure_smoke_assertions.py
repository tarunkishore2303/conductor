def assert_cached_analysis(first, second):
    if not isinstance(first, dict) or not isinstance(second, dict):
        raise ValueError("Inputs must be dictionaries")
    if 'analysisId' not in first or 'analysisId' not in second:
        raise ValueError("Dictionaries must contain 'analysisId'")
    if not isinstance(first['analysisId'], str) or not first['analysisId'] or not isinstance(second['analysisId'], str) or not second['analysisId']:
        raise ValueError("analysisId must be a nonempty string")
    if first['analysisId'] != second['analysisId']:
        raise ValueError("analysisId must be the same")
    if 'facts' not in first or 'facts' not in second:
        raise ValueError("Dictionaries must contain 'facts'")
    if not isinstance(first['facts'], dict) or not isinstance(second['facts'], dict):
        raise ValueError("facts must be a dictionary")
    if first['facts'] != second['facts']:
        raise ValueError("facts must be identical")
    return first['analysisId']
